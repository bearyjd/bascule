package com.ventouxlabs.bascule.delivery

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

interface DeliveryScheduler : DeliveryTrigger {
    fun ensurePeriodicDrain()
}

class WorkManagerDeliveryScheduler(context: Context) : DeliveryScheduler {
    private val manager = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun triggerImmediateDrain() {
        manager.enqueueUniqueWork(
            DeliveryWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DeliveryWorker>().setConstraints(network).build(),
        )
    }

    override fun ensurePeriodicDrain() {
        manager.enqueueUniquePeriodicWork(
            DeliveryWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DeliveryWorker>(DRAIN_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(network).build(),
        )
    }

    private companion object {
        /** WorkManager's own floor for periodic work; anything shorter is silently raised to it. */
        const val DRAIN_INTERVAL_MINUTES = 15L
    }
}
