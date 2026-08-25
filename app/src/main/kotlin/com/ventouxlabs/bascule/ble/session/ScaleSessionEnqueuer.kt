package com.ventouxlabs.bascule.ble.session

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * Enqueues a [ScaleSessionWorker] run for a detected advertisement. Pulled
 * out from [com.ventouxlabs.bascule.ble.ScanBroadcastReceiver] so both that
 * receiver and [com.ventouxlabs.bascule.service.BridgeForegroundService] —
 * the two real dispatch paths — share one implementation, and so a test can
 * substitute a fake instead of touching a real `WorkManager` (which needs a
 * full Robolectric+Room-backed environment this project's JVM lane cannot
 * provide — see `.claude/PRPs/plans/scale-admin-testing-completeness.plan.md`
 * Task 3's revision note).
 */
interface ScaleSessionEnqueuer {
    fun enqueue(address: String, seenAtMillis: Long)
}

class WorkManagerScaleSessionEnqueuer(context: Context) : ScaleSessionEnqueuer {
    private val manager = WorkManager.getInstance(context)

    override fun enqueue(address: String, seenAtMillis: Long) {
        val input = Data.Builder()
            .putString(ScaleSessionWorker.KEY_ADDRESS, address)
            .putLong(ScaleSessionWorker.KEY_SEEN_AT, seenAtMillis)
            .build()
        val request = OneTimeWorkRequestBuilder<ScaleSessionWorker>()
            .setInputData(input)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        manager.enqueueUniqueWork(ScaleSessionWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
