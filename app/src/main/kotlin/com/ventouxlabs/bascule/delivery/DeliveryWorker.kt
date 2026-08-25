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
        val retryNeeded = DeliveryDrainer(dao, runtime).drain()
        return if (retryNeeded) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "delivery-drain"
        const val PERIODIC_WORK_NAME = "delivery-periodic"
    }
}
