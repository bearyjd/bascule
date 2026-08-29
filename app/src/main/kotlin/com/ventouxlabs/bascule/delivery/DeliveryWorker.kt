package com.ventouxlabs.bascule.delivery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ventouxlabs.bascule.BasculeApplication

/**
 * Drains PENDING rows. Runs independently of the session process so a killed
 * service never strands a captured reading (00-design.md §8.1). Thin
 * WorkManager adapter over [DeliveryDrainer], which owns the actual logic.
 */
class DeliveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BasculeApplication
        val dao = app.database.readingDao()
        val runtime = app.runtimeApiFactory.create()
        val outcome = DeliveryDrainer(dao, runtime).drain()
        if (outcome == DrainOutcome.MORE_PAGES) app.deliveryScheduler.enqueueContinuation()
        return resultFor(outcome)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "delivery-drain"

        /**
         * Only a real failure earns WorkManager's ladder. [DrainOutcome.MORE_PAGES]
         * succeeds and re-enqueues instead, so pagination runs at full speed.
         *
         * Pure, and separated from [doWork] because a real `WorkManager` is not
         * constructible in this project's JUnit lane — see
         * [com.ventouxlabs.bascule.ble.session.WorkManagerScaleSessionEnqueuer].
         */
        fun resultFor(outcome: DrainOutcome): Result = when (outcome) {
            DrainOutcome.DONE, DrainOutcome.MORE_PAGES -> Result.success()
            DrainOutcome.FAILED -> Result.retry()
        }
    }
}
