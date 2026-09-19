package com.ventouxlabs.bascule.delivery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * The 15-minute safety net, and the retry kick after a failed drain. Does no
 * draining itself — it only asks for the one unique drain, so neither a
 * periodic wake nor a retry can ever run a [DeliveryWorker] alongside one an
 * immediate trigger already started (see [WorkManagerDeliveryScheduler]).
 *
 * Both schedules run this worker under their own names, [PERIODIC_WORK_NAME]
 * and [RETRY_KICK_WORK_NAME], never under the drain's: a request waiting under
 * `delivery-drain` — periodic, or delayed for a retry — is exactly what
 * [WorkManagerDeliveryScheduler.triggerImmediateDrain]'s KEEP would drop every
 * trigger against.
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

        /** The one-shot retry kick [DeliveryScheduler.scheduleRetryKick] enqueues; see that method for the timing. */
        const val RETRY_KICK_WORK_NAME = "delivery-retry-kick"
    }
}
