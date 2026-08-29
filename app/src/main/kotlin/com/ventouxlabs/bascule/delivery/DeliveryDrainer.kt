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
 * Splitting [MORE_PAGES] out of "retry" is what keeps WorkManager's exponential
 * ladder off healthy pagination: a `BLOCKED_AUTH` recovery of several hundred
 * rows is many consecutive full batches, none of them a failure, and running
 * them on the failure ladder stretched a minute of work across hours.
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
         * Unlike [StopDrain] this still requests a retry, so WorkManager's own
         * ladder keeps waking the worker while the row is parked. Those wakeups
         * are no-ops that stop at the `pending()` query above, and they track the
         * server's deadline far more closely than the 15-minute periodic drain
         * would — and any earlier row in this pass that failed transiently keeps
         * the retry it asked for rather than having it discarded here.
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
        val batchLimit = DeliveryCoordinator.DRAIN_BATCH_LIMIT
        val pending = dao.pending(clock(), batchLimit)
        // Nothing due: returns before `recentReadings`, so a drain triggered while
        // every row is still backing off costs one indexed query and no network.
        if (pending.isEmpty()) return DrainOutcome.DONE
        val remote = runtime.api.recentReadings(DedupPolicy.TIME_WINDOW_MILLIS.milliseconds)
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
