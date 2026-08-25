package com.ventouxlabs.bascule.delivery

import com.ventouxlabs.bascule.data.ErrorClass
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.network.RecentResult
import com.ventouxlabs.bascule.network.RuntimeApi
import com.ventouxlabs.bascule.network.SubmitResult
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

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
    }

    /** Returns true if the caller should retry the drain (a transient failure occurred). */
    suspend fun drain(): Boolean {
        val pending = dao.pending()
        if (pending.isEmpty()) return false
        val remote = runtime.api.recentReadings(DedupPolicy.TIME_WINDOW_MILLIS.milliseconds)
        var retryNeeded = false
        for (row in pending) {
            when (processRow(row, remote, clock())) {
                RowOutcome.StopDrain -> return false
                RowOutcome.RequestRetry -> retryNeeded = true
                RowOutcome.Continue -> Unit
            }
        }
        return retryNeeded
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
            abs(it.weightKg - row.weightKg) <= DedupPolicy.WEIGHT_TOLERANCE_KG &&
                abs(it.capturedAtMillis - row.capturedAtMillis) <= DedupPolicy.TIME_WINDOW_MILLIS
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
                dao.update(
                    row.copy(
                        attemptCount = row.attemptCount + 1,
                        lastAttemptMillis = now,
                        lastError = result.reason,
                        lastErrorClass = ErrorClass.TRANSIENT,
                    ),
                )
                return RowOutcome.RequestRetry
            }
        }
        return RowOutcome.Continue
    }
}
