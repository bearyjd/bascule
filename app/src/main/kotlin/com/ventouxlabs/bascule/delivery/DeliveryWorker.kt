@file:Suppress("MaxLineLength", "LoopWithTooManyJumpStatements")

package com.ventouxlabs.bascule.delivery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ErrorClass
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.network.RecentResult
import com.ventouxlabs.bascule.network.SubmitResult
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drains PENDING rows. Runs independently of the session process so a killed
 * service never strands a captured reading (00-design.md §8.1).
 *
 * PHASE 2 SKELETON. Implemented in Phase 3 WP-21.
 */
class DeliveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BasculeApplication
        val dao = app.database.readingDao()
        val runtime = app.runtimeApiFactory.create()
        var retryNeeded = false
        for (row in dao.pending()) {
            val now = System.currentTimeMillis()
            if (row.attemptCount > 0 && now - row.retryEpochMillis >= DeliveryCoordinator.EXPIRY_MILLIS) {
                dao.update(row.copy(status = ReadingStatus.FAILED_PERMANENT, lastError = "retry window expired", lastErrorClass = ErrorClass.PERMANENT))
                continue
            }
            val remote = runtime.api.recentReadings(DedupPolicy.TIME_WINDOW_MILLIS.milliseconds)
            val duplicate = remote is RecentResult.Readings && remote.readings.any {
                abs(it.weightKg - row.weightKg) <= DedupPolicy.WEIGHT_TOLERANCE_KG &&
                    abs(it.capturedAtMillis - row.capturedAtMillis) <= DedupPolicy.TIME_WINDOW_MILLIS
            }
            if (duplicate) {
                dao.update(row.copy(status = ReadingStatus.SENT, remoteDuplicate = true, lastAttemptMillis = now))
                continue
            }
            when (val result = runtime.api.submitReading(row, runtime.unit)) {
                is SubmitResult.Accepted -> dao.update(row.copy(
                    status = ReadingStatus.SENT,
                    attemptCount = row.attemptCount + 1,
                    lastAttemptMillis = now,
                    lastError = null,
                    lastErrorClass = null,
                    deliveredFields = result.deliveredFields,
                    contractVersionAtDelivery = runtime.api.contract.wire,
                ))
                is SubmitResult.AuthRejected -> {
                    dao.blockAllPendingForAuth()
                    return Result.success()
                }
                is SubmitResult.PermanentRejection -> dao.update(row.copy(
                    status = ReadingStatus.FAILED_PERMANENT,
                    attemptCount = row.attemptCount + 1,
                    lastAttemptMillis = now,
                    lastError = "server rejected reading (${result.httpCode})",
                    lastErrorClass = ErrorClass.PERMANENT,
                ))
                is SubmitResult.TransientFailure -> {
                    dao.update(row.copy(
                        attemptCount = row.attemptCount + 1,
                        lastAttemptMillis = now,
                        lastError = result.reason,
                        lastErrorClass = ErrorClass.TRANSIENT,
                    ))
                    retryNeeded = true
                }
            }
        }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "delivery-drain"
        const val PERIODIC_WORK_NAME = "delivery-periodic"
    }
}
