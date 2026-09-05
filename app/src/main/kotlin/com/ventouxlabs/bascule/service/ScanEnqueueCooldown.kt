package com.ventouxlabs.bascule.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Gates repeat session enqueues for one device address. Without it every
 * advertisement — 2-10 per second while the scale is in radio range — starts a
 * fresh GATT connect/handshake cycle the moment the previous one finishes,
 * because `ExistingWorkPolicy` only suppresses work that is actually in flight.
 *
 * The window is *claimed* when a session is enqueued and [settle]d when that
 * session ends. It is sized well past `SessionBudget`'s 90s hard ceiling so
 * that even a session running to that ceiling leaves several minutes of quiet
 * behind it.
 *
 * Stamping at enqueue alone used to hold a failed address down for the whole
 * window, so a session that aborted in milliseconds swallowed every retry for
 * five minutes — including the user stepping straight back on the scale, which
 * is the only recovery they actually have. Worse, it gated the defense
 * `ScaleSessionWorker` already had against scheduler latency:
 * `existingWorkPolicyFor` REPLACEs merely-queued work so that a fresh
 * advertisement refreshes `seenAt`, but while the window was shut no
 * advertisement ever reached the enqueuer, so a staleness abort could not be
 * recovered from at all. The worker now reports its terminal disposition back
 * here, and the three cases are genuinely different — see [CooldownDisposition].
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
    private val failureBackoffMillis: Long = DEFAULT_FAILURE_BACKOFF_MILLIS,
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
     *
     * Prunes every other address whose window has already elapsed while
     * writing this claim (devil's-advocate review, security round 5): this
     * file is durable, unlike the in-memory map it replaced, so every scale
     * this device has ever cooled down for would otherwise accumulate here
     * forever — a small but real, and entirely avoidable, amount of plaintext
     * BLE-address/timestamp residue on disk.
     */
    @Synchronized
    fun claim(address: String): Boolean {
        val now = clock()
        val last = store.getLong(address, Long.MIN_VALUE)
        // `in 0 until` rather than `<`: the stamp now outlives the process, so a
        // wall-clock correction backwards would otherwise suppress every claim
        // until real time caught up to the stale stamp.
        if (last != Long.MIN_VALUE && now - last in 0 until windowMillis) return false
        store.edit().apply {
            store.all.keys
                .filter { it != address && now - store.getLong(it, Long.MIN_VALUE) !in 0 until windowMillis }
                .forEach(::remove)
            putLong(address, now)
        }.commit()
        return true
    }

    /**
     * Reports how the session that [claim]ed [address] actually ended, so the
     * window reflects the outcome instead of the mere attempt.
     *
     * Writes with `commit()` for the same reason [claim] does: this runs at the
     * tail of a worker whose process the platform may reap as soon as it
     * returns, and a release still sitting in the async write queue is a
     * release the next advertisement does not see — precisely the lockout this
     * method exists to end.
     *
     * [CooldownDisposition.BACKOFF] is expressed by back-dating the existing
     * claim rather than by storing an expiry, so the stored format stays "the
     * instant this address was claimed" and [claim]'s clock-correction guard
     * keeps working unchanged.
     */
    @Synchronized
    fun settle(address: String, disposition: CooldownDisposition) {
        when (disposition) {
            CooldownDisposition.HOLD -> Unit
            CooldownDisposition.RELEASE -> store.edit().remove(address).commit()
            CooldownDisposition.BACKOFF -> {
                val remaining = failureBackoffMillis.coerceIn(0, windowMillis)
                store.edit().putLong(address, clock() - (windowMillis - remaining)).commit()
            }
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 5L * 60 * 1_000

        /**
         * How long an address stays quiet after a session that reached the
         * radio and failed. Long enough that a failing scale is not reconnected
         * to on every advertisement — the burst rate is 2-10/s — and short
         * enough that a user who steps off, waits, and steps back on gets a
         * real second attempt rather than silence.
         */
        const val DEFAULT_FAILURE_BACKOFF_MILLIS = 20L * 1_000
        private const val PREFS_NAME = "scan_enqueue_cooldown"
    }
}

/**
 * How a finished scale session should leave the cooldown window it claimed.
 *
 * Three cases, because they are genuinely different situations and collapsing
 * any two of them reintroduces a real defect:
 *
 * - [HOLD] earned the full window. A reading landed, so a second session would
 *   only re-capture what is already stored; or the device answered and proved
 *   incompatible, which retrying cannot change.
 * - [BACKOFF] reached the radio and came back empty, or was refused by a
 *   condition that will not change within a few advertisements — a missing
 *   permission, a mismatched profile, a refused foreground start. Reconnecting
 *   on every advertisement would be the original defect this file exists to
 *   prevent; never retrying loses the weigh-in.
 * - [RELEASE] never reached the radio, so there is nothing to back off from.
 *   Reserved for the staleness abort, where releasing is exactly what lets the
 *   *next* advertisement enqueue with a fresh `seenAt` and actually connect. It
 *   is self-throttling despite the name: a fresh claim needs a full worker
 *   dispatch first, so the loop runs at the scheduler's rate rather than the
 *   advertisement rate.
 */
internal enum class CooldownDisposition {
    HOLD,
    BACKOFF,
    RELEASE,
}
