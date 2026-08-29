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

    /**
     * Enqueues the next page of a drain that is still running. Distinct from
     * [triggerImmediateDrain] because that one keeps the in-flight run and drops
     * the new request — which is right for a trigger and fatal for a
     * continuation, since the continuation is enqueued *by* the run it would be
     * deduped against.
     */
    fun enqueueContinuation()
}

/**
 * Every drain runs under one unique work name, [DeliveryWorker.UNIQUE_WORK_NAME].
 *
 * `ExistingWorkPolicy.KEEP` only dedupes within a name, so the periodic schedule
 * cannot own a second one: two names meant a periodic drain and a triggered
 * drain could run concurrently, both select the same PENDING rows, and both
 * submit them — a duplicate weigh-in the user cannot retract, because v1 sends
 * no idempotency key for the server to dedupe on.
 *
 * The periodic schedule therefore runs [DeliveryPeriodicKickWorker], which does
 * nothing but enqueue that one unique drain. A [PeriodicWorkRequestBuilder]
 * request could not take the name itself: periodic work never reaches a finished
 * state, so `KEEP` against it would drop every immediate trigger forever.
 */
class WorkManagerDeliveryScheduler(context: Context) : DeliveryScheduler {
    private val manager = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun triggerImmediateDrain() {
        manager.enqueueUniqueWork(
            DeliveryWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            drainRequest(),
        )
    }

    override fun enqueueContinuation() {
        manager.enqueueUniqueWork(
            DeliveryWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            drainRequest(),
        )
    }

    override fun ensurePeriodicDrain() {
        manager.enqueueUniquePeriodicWork(
            DeliveryPeriodicKickWorker.PERIODIC_WORK_NAME,
            // UPDATE, not KEEP: an install that already registered this name
            // against the old worker class would otherwise keep running a
            // second, concurrent drain under it.
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DeliveryPeriodicKickWorker>(DRAIN_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(network).build(),
        )
    }

    private fun drainRequest() =
        OneTimeWorkRequestBuilder<DeliveryWorker>().setConstraints(network).build()

    private companion object {
        /** WorkManager's own floor for periodic work; anything shorter is silently raised to it. */
        const val DRAIN_INTERVAL_MINUTES = 15L
    }
}
