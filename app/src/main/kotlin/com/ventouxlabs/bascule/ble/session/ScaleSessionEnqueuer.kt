package com.ventouxlabs.bascule.ble.session

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.Executor
import java.util.concurrent.Executors

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
    /**
     * [onEnqueued] fires once the request is durably recorded by WorkManager,
     * or once the attempt has failed — never both, and not necessarily on the
     * calling thread. [ScanBroadcastReceiver][com.ventouxlabs.bascule.ble.ScanBroadcastReceiver]
     * holds its `goAsync()` window open until it fires, because a process woken
     * solely for that broadcast can be killed before the write lands.
     */
    fun enqueue(address: String, seenAtMillis: Long, onEnqueued: () -> Unit = {})
}

class WorkManagerScaleSessionEnqueuer(context: Context) : ScaleSessionEnqueuer {
    private val manager = WorkManager.getInstance(context)

    /**
     * `enqueue` is called from [com.ventouxlabs.bascule.ble.ScanBroadcastReceiver.onReceive]
     * (the main thread, by default, for a manifest-registered receiver) and
     * from a BLE scan callback (a binder thread). Deciding [ExistingWorkPolicy]
     * needs one query against WorkManager's own database first — resolving it
     * via [addListener] rather than blocking with `.get()` keeps that query off
     * whichever of those threads called this, at the cost of one extra hop
     * before the work is actually enqueued.
     */
    override fun enqueue(address: String, seenAtMillis: Long, onEnqueued: () -> Unit) {
        val request = OneTimeWorkRequestBuilder<ScaleSessionWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ScaleSessionWorker.KEY_ADDRESS, address)
                    .putLong(ScaleSessionWorker.KEY_SEEN_AT, seenAtMillis)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        val existingWork = manager.getWorkInfosForUniqueWork(ScaleSessionWorker.UNIQUE_WORK_NAME)
        existingWork.addListener(
            {
                val policy = runCatching { existingWorkPolicyFor(existingWork.get().map { it.state }) }
                    .getOrDefault(ExistingWorkPolicy.KEEP)
                // The Operation's future completes when the request has been
                // written to WorkManager's own database — the point past which
                // losing this process no longer loses the wake. Any throw from
                // the enqueue itself has to signal too, or the caller's
                // goAsync() window is held until its timeout for nothing.
                runCatching {
                    manager.enqueueUniqueWork(ScaleSessionWorker.UNIQUE_WORK_NAME, policy, request)
                        .result.addListener({ onEnqueued() }, policyExecutor)
                }.onFailure { onEnqueued() }
            },
            policyExecutor,
        )
    }

    private companion object {
        /**
         * Dedicated rather than shared: this class is its only user, and a
         * single background thread is enough to resolve one query and enqueue
         * one request per advertisement — the whole point is keeping this work
         * off the caller's thread, not making it fast.
         *
         * Daemon, because nothing ever shuts this executor down: it is a
         * process-lifetime singleton, and a non-daemon thread would keep the
         * JVM alive past the last other thread in a unit-test lane.
         */
        val policyExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "scale-session-enqueue").apply { isDaemon = true }
        }
    }
}

/**
 * A session that is actually running is worth keeping — replacing it would
 * cancel a live GATT connection mid-capture. One that is merely *enqueued* is
 * not: [ScaleSessionWorker] aborts on a `seenAt` older than its 20s staleness
 * budget, so keeping a request that has been sitting in the queue (expedited
 * work still competes with ordinary scheduling, especially in Doze) discards
 * the live advertisement and then gives up without connecting.
 */
internal fun existingWorkPolicyFor(states: List<WorkInfo.State>): ExistingWorkPolicy =
    if (states.any { it == WorkInfo.State.RUNNING }) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
