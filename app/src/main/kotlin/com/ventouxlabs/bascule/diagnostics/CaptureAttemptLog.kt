package com.ventouxlabs.bascule.diagnostics

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a capture attempt did, in the terms the person standing on the scale
 * cares about rather than the terms the GATT stack failed in.
 *
 * Deliberately coarser than `SessionExitReason`: a user cannot act on
 * "DISCOVERY_FAILED" versus "GRACEFUL_DISCONNECT", but they can act on "the
 * phone never got there in time" versus "your scale isn't linked yet". The
 * fine-grained reason still goes to logcat for whoever is debugging.
 */
enum class CaptureOutcome {
    /** A reading was captured and stored. */
    CAPTURED,

    /** The scale was reached, but the session ended without a reading. */
    NO_READING,

    /**
     * The phone never reached the radio in time — the advertisement went stale
     * waiting for a scheduling slot, or a foreground start was refused. A
     * statement about the phone's scheduler, not about the scale.
     */
    MISSED_THE_WINDOW,

    /** Not set up to capture: no active profile, no permission, Bluetooth off. */
    NOT_READY,

    /** The device answered and is not a scale this app can talk to. */
    INCOMPATIBLE,

    /** The attempt ended in an unexpected failure and classified itself no further. */
    FAILED,

    /** Connected and listened for the whole window; nobody stepped on. */
    IDLE,

    /** The scale asked this phone to pair and nobody accepted Android's request. Needs the user. */
    NEEDS_PAIRING,
}

data class LastCaptureAttempt(val atMillis: Long, val outcome: CaptureOutcome)

/**
 * The last thing an automatic capture attempt did, surviving the process that
 * did it.
 *
 * This exists because the app had no answer to "I stepped on the scale and
 * nothing happened." `DiagnosticsCounters`' only implementation is in-memory —
 * its own KDoc calls it the test double standing in until WP-26 — so every
 * counter died with the worker process that incremented it, and a session that
 * reached the radio and came back empty was indistinguishable, from outside,
 * from one that never ran at all.
 *
 * Scoped to one attempt rather than a full counter registry on purpose: the
 * single most recent outcome is what makes a silent failure legible, and it is
 * cheap enough to write on every exit from a worker.
 */
interface CaptureAttemptLog {
    fun record(outcome: CaptureOutcome, atMillis: Long = System.currentTimeMillis())
    val last: StateFlow<LastCaptureAttempt?>
}

/**
 * [SharedPreferences]-backed for the same reason `ScanEnqueueCooldown` is: the
 * writer is a worker whose process the platform may reap the moment it returns,
 * and the reader is the UI. Both live in the default process — the manifest
 * declares no `android:process` — so a change listener is enough to keep [last]
 * live without polling.
 */
class SharedPreferencesCaptureAttemptLog(context: Context) : CaptureAttemptLog {

    private val store: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val mutableLast = MutableStateFlow(read())

    override val last: StateFlow<LastCaptureAttempt?> = mutableLast.asStateFlow()

    /**
     * Held in a field, not passed inline: [SharedPreferences] keeps only a weak
     * reference to its listeners, so an inline lambda would be collected and
     * the flow would silently stop updating.
     */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mutableLast.value = read()
    }

    init {
        store.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * `commit()` rather than `apply()`: this is written on the way out of a
     * worker, and a record still sitting in the async write queue when that
     * process is reaped is exactly the silent failure this class exists to make
     * visible.
     */
    override fun record(outcome: CaptureOutcome, atMillis: Long) {
        store.edit()
            .putString(KEY_OUTCOME, outcome.name)
            .putLong(KEY_AT, atMillis)
            .commit()
    }

    /**
     * An unreadable record reads as "no attempt yet" rather than throwing: this
     * is a diagnostic surface, and it must never be the reason a screen fails
     * to render. A name written by a newer build than this one is the realistic
     * case, after a downgrade.
     */
    private fun read(): LastCaptureAttempt? {
        val at = store.getLong(KEY_AT, 0L)
        if (at <= 0L) return null
        val name = store.getString(KEY_OUTCOME, null) ?: return null
        val outcome = CaptureOutcome.entries.firstOrNull { it.name == name } ?: return null
        return LastCaptureAttempt(at, outcome)
    }

    private companion object {
        const val PREFS_NAME = "capture_attempts"
        const val KEY_OUTCOME = "last_outcome"
        const val KEY_AT = "last_at_millis"
    }
}
