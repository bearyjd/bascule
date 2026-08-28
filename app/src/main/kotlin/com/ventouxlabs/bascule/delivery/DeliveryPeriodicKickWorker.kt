package com.ventouxlabs.bascule.delivery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * The 15-minute safety net. Does no draining itself — it only asks for the one
 * unique drain, so a periodic wake can never run a [DeliveryWorker] alongside
 * one an immediate trigger already started (see [WorkManagerDeliveryScheduler]).
 */
class DeliveryPeriodicKickWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        WorkManagerDeliveryScheduler(applicationContext).triggerImmediateDrain()
        return Result.success()
    }

    companion object {
        const val PERIODIC_WORK_NAME = "delivery-periodic"
    }
}
