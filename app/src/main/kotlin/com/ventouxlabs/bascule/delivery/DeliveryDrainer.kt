package com.ventouxlabs.bascule.delivery

import com.ventouxlabs.bascule.data.ErrorClass
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.network.RecentResult
import com.ventouxlabs.bascule.network.RuntimeApi
import com.ventouxlabs.bascule.network.SubmitResult
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a [DeliveryDrainer.drain] pass wants to happen next.
 *
 * [MORE_PAGES] and [FAILED] are kept apart because they want opposite things
 * from [DeliveryWorker]: a continuation queued right now, or a kick delayed
 * until the rows' §3.4 backoff has run out. A `BLOCKED_AUTH` recovery of
 * several hundred rows is many consecutive full batches, none of them a
 * failure, and back when both mapped to WorkManager's `Result.retry()` its
 * exponential ladder stretched that minute of work across hours.
 */
enum class DrainOutcome {
    /** Nothing left to do until something new is captured or the periodic drain fires. */
    DONE,

    /** The batch filled up; rows remain. Re-run immediately, with no backoff. */
    MORE_PAGES,

    /** Something failed transiently, or a server asked us to slow down. Back off. */
    FAILED,
}

/**
 * Drains PENDING rows. Plain and worker-independent so it is unit-testable
 * without WorkManager/CoroutineWorker; [DeliveryWorker] is a thin adapter.
 *
 * The remote-duplicate check is fetched once per drain, not once per row —
 * [RuntimeApi.api]'s `recentReadings` response depends only on "now", not on
 * which row is being checked, so refetching it per row was a pure N+1 with no
 * behavioral benefit.
 */
class DeliveryDrainer(
    private val dao: ReadingDao,
    private val runtime: RuntimeApi,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * A sink rather than `android.util.Log` directly, for the same reason as
     * `GattSession`'s: this class runs in the plain JVM lane, where `Log` is
     * unmocked and throws. [DeliveryWorker] wires it to logcat.
     */
    private val log: (String) -> Unit = {},
) {
    private sealed interface RowOutcome {
        data object Continue : RowOutcome
        data object StopDrain : RowOutcome
        data object RequestRetry : RowOutcome

        /**
         * The server asked us to slow down (`Retry-After`). Submitting the rest
         * of the batch would walk straight into the same rate limit, so the pass
         * ends here and asks to be resumed later.
         *
         * Unlike [StopDrain] this still reports a failure, so [DeliveryWorker]
         * schedules its retry kick: at this row's server deadline when that is
         * still ahead, or one ladder base out when the deadline has already
         * passed (a `Retry-After` of zero parks the row at `now`, with the rest
         * of the batch still due behind it) — never left to the 15-minute
         * periodic net, and never tighter than the base even against a server
         * that answers zero forever. Any earlier row in this pass that failed
         * transiently keeps the kick it asked for rather than having it
         * discarded here.
         */
        data object BackOffDrain : RowOutcome
    }

    /**
     * Drains one batch and reports what should happen next.
     *
     * Only rows whose §3.4 backoff has elapsed are selected, so an unrelated
     * trigger (a manual entry saved, a token saved, a scale capture, a History
     * retry tap) no longer resubmits the whole pending set on top of a backoff
     * that has not run out.
     */
    suspend fun drain(): DrainOutcome {
        recoverRowsRejectedUnderAnotherContract()
        val batchLimit = DeliveryCoordinator.DRAIN_BATCH_LIMIT
        val pending = dao.pending(clock(), batchLimit)
        // Nothing due: returns before `recentReadings`, so a drain triggered while
        // every row is still backing off costs one indexed query and no network.
        if (pending.isEmpty()) return DrainOutcome.DONE
        val remote = runtime.api.recentReadings(DedupPolicy.TIME_WINDOW_MILLIS.milliseconds)
        // The one line that separates "the check ran and matched nothing" from
        // "the check could not run" (00-design.md §8.3 step 3). Without it an
        // endpoint that answers in a shape the client cannot read is
        // indistinguishable, from outside the process, from a server with no
        // rows — which is how the check sat inert for a month. The reason is
        // one of the client's own fixed phrases, never response text (§8.8).
        if (remote is RecentResult.Unavailable) {
            log("remote duplicate check unavailable (${remote.reason}); posting anyway")
        }
        var failed = false
        for (row in pending) {
            when (processRow(row, remote, clock())) {
                RowOutcome.StopDrain -> return DrainOutcome.DONE
                RowOutcome.BackOffDrain -> return DrainOutcome.FAILED
                RowOutcome.RequestRetry -> failed = true
                RowOutcome.Continue -> Unit
            }
        }
        // A full batch means the query hit its LIMIT, not that the queue is
        // empty. Ask to be run again rather than letting the 10-minute
        // WorkManager ceiling be what decides where this drain stopped. A
        // failure anywhere in the batch outranks that: whatever went wrong
        // would meet the next page too, so back off rather than paginate into it.
        return when {
            failed -> DrainOutcome.FAILED
            pending.size == batchLimit -> DrainOutcome.MORE_PAGES
            else -> DrainOutcome.DONE
        }
    }

    /**
     * The backstop for `ConfigViewModel.saveContractVersion`'s own recovery:
     * that call reads the target contract once, at the moment the user
     * switches, but a drain already in flight under the *old* contract can
     * still stamp a fresh 422 after that one-time query has already run —
     * the row then sits `FAILED_PERMANENT` under the very version the user
     * just switched away from, unrecovered until they touch the setting
     * again (Codex review, v2-body-composition PR, 4th pass). This runs on
     * every drain — periodic, and immediate after every capture — reading
     * `runtime.api.contract` fresh each time, the same value the batch below
     * is about to submit under, so there is no gap for a race to live in.
     * A no-op query when nothing is stranded, which is the common case.
     *
     * Residual, not silently closed (Codex review, v2-body-composition PR,
     * 5th pass): if the switch lands while *this* drain is already running,
     * `DeliveryScheduler.triggerImmediateDrain`'s `ExistingWorkPolicy.KEEP`
     * silently drops the new trigger — a run already in flight always wins,
     * by the same design that keeps a periodic and a triggered drain from
     * ever running concurrently and double-submitting (see that class's own
     * KDoc). A row rejected in that exact window recovers at the next
     * periodic drain (15 min) or capture-triggered one — or at the retry
     * kick, when the same pass also failed transiently — not immediately.
     * Never lost — durably PENDING or FAILED_PERMANENT throughout — and
     * closing the last of it would mean a dedicated follow-up worker for a
     * race narrower than a single drain's own runtime, which is not
     * warranted by what it costs the user: a bounded delay, not data loss.
     */
    private suspend fun recoverRowsRejectedUnderAnotherContract() {
        val stranded = dao.failedPermanentlyUnderOtherContract(runtime.api.contract.wire)
        if (stranded.isNotEmpty()) dao.requeueForReplay(stranded, clock())
    }

    private suspend fun processRow(row: ReadingEntity, remote: RecentResult, now: Long): RowOutcome {
        if (row.attemptCount > 0 && now - row.retryEpochMillis >= DeliveryCoordinator.EXPIRY_MILLIS) {
            dao.update(
                row.copy(
                    status = ReadingStatus.FAILED_PERMANENT,
                    lastError = "retry window expired",
                    lastErrorClass = ErrorClass.PERMANENT,
                ),
            )
            return RowOutcome.Continue
        }
        if (isRemoteDuplicate(row, remote)) {
            dao.update(row.copy(status = ReadingStatus.SENT, remoteDuplicate = true, lastAttemptMillis = now))
            return RowOutcome.Continue
        }
        return applySubmitResult(row, runtime.api.submitReading(row, runtime.unit), now)
    }

    /**
     * The ADR-003 remote-duplicate check (00-design.md §8.3 step 2). [remote]
     * is the server's last ten rows, not a windowed set — the server ignores
     * the `within` the client sent — so the time half of
     * [DedupPolicy.withinTolerance] is what applies the 5-minute window here.
     * `Unavailable` is never a duplicate — the row posts — and [drain] has
     * already logged it once, so that outcome is not silent.
     */
    private fun isRemoteDuplicate(row: ReadingEntity, remote: RecentResult): Boolean =
        remote is RecentResult.Readings && remote.readings.any {
            DedupPolicy.withinTolerance(it.weightKg, row.weightKg, it.capturedAtMillis, row.capturedAtMillis)
        }

    private suspend fun applySubmitResult(row: ReadingEntity, result: SubmitResult, now: Long): RowOutcome {
        when (result) {
            is SubmitResult.Accepted -> dao.update(
                row.copy(
                    status = ReadingStatus.SENT,
                    attemptCount = row.attemptCount + 1,
                    lastAttemptMillis = now,
                    lastError = null,
                    lastErrorClass = null,
                    deliveredFields = result.deliveredFields,
                    contractVersionAtDelivery = runtime.api.contract.wire,
                ),
            )
            is SubmitResult.AuthRejected -> {
                dao.blockAllPendingForAuth()
                return RowOutcome.StopDrain
            }
            is SubmitResult.PermanentRejection -> dao.update(
                row.copy(
                    status = ReadingStatus.FAILED_PERMANENT,
                    attemptCount = row.attemptCount + 1,
                    lastAttemptMillis = now,
                    lastError = "server rejected reading (${result.httpCode})",
                    lastErrorClass = ErrorClass.PERMANENT,
                    // Codex review, v2-body-composition PR: without this, a
                    // 422 caused by the *contract* (the realistic case this
                    // whole recovery path exists for) left the row
                    // indistinguishable from one rejected on its own merits —
                    // ConfigViewModel.saveContractVersion's requeue query reads
                    // this column and would never have matched a real
                    // rejection, only the hand-stamped rows in its own tests.
                    contractVersionAtDelivery = runtime.api.contract.wire,
                    // Which permanent code, so the same recovery query can
                    // tell a schema rejection (422, contract-fixable) apart
                    // from the others PermanentRejection also covers, none of
                    // which a contract switch can do anything about.
                    permanentRejectionHttpCode = result.httpCode,
                ),
            )
            is SubmitResult.TransientFailure -> {
                val attemptCount = row.attemptCount + 1
                dao.update(
                    row.copy(
                        attemptCount = attemptCount,
                        lastAttemptMillis = now,
                        lastError = result.reason,
                        lastErrorClass = ErrorClass.TRANSIENT,
                        nextAttemptMillis = DeliveryCoordinator.nextAttemptMillis(
                            now = now,
                            attemptCount = attemptCount,
                            retryAfter = result.retryAfter,
                        ),
                    ),
                )
                return if (result.retryAfter != null) RowOutcome.BackOffDrain else RowOutcome.RequestRetry
            }
        }
        return RowOutcome.Continue
    }
}
