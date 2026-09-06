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
 * session ends. It is sized past `SessionBudget.HARD_SESSION_CEILING` so that
 * a session running to the ceiling cannot have its own advertisements re-enqueue
 * against it — see [DEFAULT_WINDOW_MILLIS].
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
                .filter { !it.startsWith(FAIL_PREFIX) }
                .filter { it != address && now - store.getLong(it, Long.MIN_VALUE) !in 0 until windowMillis }
                .forEach { stale ->
                    remove(stale)
                    // The streak is meaningless once its address is gone, and
                    // left behind it would be indistinguishable from a real one
                    // when that scale is next seen.
                    remove(FAIL_PREFIX + stale)
                }
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
            // A capture earns the full window, and ends whatever streak
            // preceded it: the next failure after a success is a first failure.
            CooldownDisposition.HOLD -> store.edit().remove(FAIL_PREFIX + address).commit()
            CooldownDisposition.RELEASE -> clear(address)
            // Flat and streak-neutral: an idle listen is not a failure, and
            // escalating on it would open ever-longer gaps in exactly the
            // coverage a long-lived session exists to provide.
            CooldownDisposition.PAUSE ->
                store.edit().putLong(address, clock() - (windowMillis - backoffFor(1))).commit()
            CooldownDisposition.BACKOFF -> {
                val streak = store.getLong(FAIL_PREFIX + address, 0L) + 1
                val remaining = backoffFor(streak)
                store.edit()
                    .putLong(address, clock() - (windowMillis - remaining))
                    .putLong(FAIL_PREFIX + address, streak)
                    .commit()
            }
        }
    }

    /**
     * Drops the window and the streak for [address] outright.
     *
     * The escape hatch for explicit user intent: someone who has just pressed
     * "Weigh now" is standing on the scale, and a throttle earned by earlier
     * failures must not be what stops that from working. Without it the
     * escalation below would eventually block the one control the user has.
     */
    @Synchronized
    fun clear(address: String) {
        store.edit().remove(address).remove(FAIL_PREFIX + address).commit()
    }

    /**
     * Doubles per consecutive failure — 20s, 40s, 80s, … — capped at the full
     * window.
     *
     * A flat backoff is wrong at both ends. A scale that advertises
     * continuously and has no measurement to give (the phone is near it, nobody
     * is standing on it) would be reconnected to every ~70s forever, which is
     * the reconnect storm this file exists to prevent, merely slower. A flat
     * *long* backoff loses the retry that makes stepping back on work. Doubling
     * keeps the first retry quick, where it is nearly always the one that
     * matters, and decays to the full window if the scale genuinely
     * has nothing to say.
     */
    private fun backoffFor(streak: Long): Long {
        val shift = (streak - 1).coerceIn(0, MAX_BACKOFF_DOUBLINGS)
        val scaled = failureBackoffMillis shl shift.toInt()
        return scaled.coerceIn(0, windowMillis)
    }

    companion object {
        /**
         * Must exceed `SessionBudget.HARD_SESSION_CEILING`: the claim is what
         * stops the 2-10/s advertisement burst from re-enqueueing against a
         * session that is still running. A window shorter than the session
         * would expire mid-listen and turn every remaining packet into a
         * WorkManager query that resolves to KEEP — thousands of them.
         */
        const val DEFAULT_WINDOW_MILLIS = 10L * 60 * 1_000

        /**
         * How long an address stays quiet after a session that reached the
         * radio and failed. Long enough that a failing scale is not reconnected
         * to on every advertisement — the burst rate is 2-10/s — and short
         * enough that a user who steps off, waits, and steps back on gets a
         * real second attempt rather than silence.
         */
        const val DEFAULT_FAILURE_BACKOFF_MILLIS = 20L * 1_000

        /**
         * Five doublings takes 20s to 640s, past the window, so the cap is the
         * window itself and further failures cannot extend it.
         */
        const val MAX_BACKOFF_DOUBLINGS = 5L
        private const val PREFS_NAME = "scan_enqueue_cooldown"

        /**
         * Namespaces the consecutive-failure counters so they cannot be
         * mistaken for claim stamps by [claim]'s pruning pass, which reads
         * every other key as a timestamp.
         */
        private const val FAIL_PREFIX = "fails:"
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

    /**
     * A short, flat, streak-neutral pause. For a session that listened its
     * whole budget and heard nothing: not a failure, so it must not escalate —
     * the point of a long listen is to be connected when the user steps on,
     * and an escalating gap between listens is exactly the coverage hole that
     * made capture a lottery.
     */
    PAUSE,
}
