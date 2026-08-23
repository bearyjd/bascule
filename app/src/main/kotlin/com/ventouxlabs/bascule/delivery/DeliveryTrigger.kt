package com.ventouxlabs.bascule.delivery

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * A single-method seam around [WorkManager] so callers that only need to
 * kick off an immediate drain (`00-design.md` §8.6) can be unit-tested
 * against a fake, the same way [com.ventouxlabs.bascule.ble.session.ConsentStore]
 * and [com.ventouxlabs.bascule.data.ConfigStore] are — `androidx.work:work-testing`
 * is `androidTestImplementation` only in this project, so a real
 * [WorkManager] is not reachable from a plain JUnit test.
 */
interface DeliveryTrigger {
    /** Enqueues [DeliveryWorker] under its unique work name, keeping any run already in flight. */
    fun triggerImmediateDrain()
}

class WorkManagerDeliveryTrigger(context: Context) : DeliveryTrigger {

    private val workManager = WorkManager.getInstance(context)

    override fun triggerImmediateDrain() {
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>().build()
        workManager.enqueueUniqueWork(DeliveryWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
