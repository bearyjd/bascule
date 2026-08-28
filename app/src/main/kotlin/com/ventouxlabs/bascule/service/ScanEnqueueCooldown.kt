package com.ventouxlabs.bascule.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Gates repeat session enqueues for one device address. Without it every
 * advertisement — 2-10 per second while the scale is in radio range — starts a
 * fresh GATT connect/handshake cycle the moment the previous one finishes,
 * because `ExistingWorkPolicy` only suppresses work that is actually in flight.
 *
 * The window is stamped when a session is *enqueued* rather than when it ends:
 * the terminal outcome is known only inside `ScaleSessionWorker`, one process
 * hop away from both callers. It is sized well past `SessionBudget`'s 90s hard
 * ceiling so that even a session running to that ceiling leaves several minutes
 * of quiet behind it. Known cost of stamping early: a session that aborts in
 * milliseconds — a transient adapter-off, say — still holds the address down
 * for the full window, so the next step-on is missed. Closing that would mean
 * reporting the outcome back from the worker, which is a larger change than the
 * defect warrants; the adapter-off case already returns `Result.retry()` and so
 * re-attempts without needing a fresh advertisement.
 *
 * Backed by [SharedPreferences] rather than a field, because the two callers
 * cannot share memory reliably: `ScanBroadcastReceiver` is manifest-declared,
 * so the framework builds a fresh instance per broadcast and may have cold
 * started the process to do it. Disk also makes the window shared between the
 * two wake paths — `BridgeForegroundService`'s scan and the `PendingIntent`
 * scan can be armed at once, and one session per address per window across both
 * is the behavior actually wanted.
 */
internal class ScanEnqueueCooldown(
    private val store: SharedPreferences,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    /**
     * Reserves the next session for [address], or returns false while the
     * window is open. Writes with `commit()`, not `apply()`: the receiver path
     * runs in a process that exists only to service one broadcast, and a claim
     * still sitting in the async write queue when that process dies is a claim
     * the next advertisement does not see.
     */
    @Synchronized
    fun claim(address: String): Boolean {
        val now = clock()
        val last = store.getLong(address, Long.MIN_VALUE)
        // `in 0 until` rather than `<`: the stamp now outlives the process, so a
        // wall-clock correction backwards would otherwise suppress every claim
        // until real time caught up to the stale stamp.
        if (last != Long.MIN_VALUE && now - last in 0 until windowMillis) return false
        store.edit().putLong(address, now).commit()
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 5L * 60 * 1_000
        private const val PREFS_NAME = "scan_enqueue_cooldown"
    }
}
