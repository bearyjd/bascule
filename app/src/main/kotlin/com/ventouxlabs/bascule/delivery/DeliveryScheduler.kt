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

    /**
     * Schedules one [DeliveryPeriodicKickWorker] run after [delayMillis]; a
     * later call replaces an earlier one. This is the retry pacing after a
     * failed drain: [DeliveryWorker] times it from the rows' own §3.4
     * `nextAttemptMillis`, so the row ladder — not WorkManager's — decides when
     * the next attempt happens.
     *
     * Replacing is safe because every call recomputes the delay from the soonest
     * row still waiting (or the ladder's base when no row is waiting): a
     * replacement can only be as late as that row, never later than a kick it
     * displaces was still needed for.
     */
    fun scheduleRetryKick(delayMillis: Long)
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
 *
 * The retry kick after a failed drain is the same shape for the same reason. A
 * `Result.retry()` from [DeliveryWorker] used to park a delayed request under
 * the drain name in WorkManager's own backoff (30 s doubling to a 5-hour cap),
 * and `KEEP` dropped every trigger — a new capture, a saved token, even the
 * periodic kick — for as long as it sat there. A delayed request must never
 * hold the drain name, so the retry is a kick under
 * [DeliveryPeriodicKickWorker.RETRY_KICK_WORK_NAME] instead.
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

    override fun scheduleRetryKick(delayMillis: Long) {
        manager.enqueueUniqueWork(
            DeliveryPeriodicKickWorker.RETRY_KICK_WORK_NAME,
            // REPLACE, not KEEP: the latest failure recomputed the delay from
            // the rows, so it is the one that knows when the soonest waiting
            // row is due. A kept stale kick would fire at the wrong time.
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DeliveryPeriodicKickWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(network)
                .build(),
        )
    }

    private fun drainRequest() =
        OneTimeWorkRequestBuilder<DeliveryWorker>().setConstraints(network).build()

    private companion object {
        /** WorkManager's own floor for periodic work; anything shorter is silently raised to it. */
        const val DRAIN_INTERVAL_MINUTES = 15L
    }
}
