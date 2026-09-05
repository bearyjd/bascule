package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.service.CooldownDisposition
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The policy half of the fix for the scan-cooldown lockout: a session used to
 * hold its scale's address for the full five-minute window no matter how it
 * ended, so one failure swallowed every retry — including the user stepping
 * straight back on the scale.
 *
 * A plain JVM test, deliberately: `ScaleSessionWorker` itself is unreachable
 * from this lane (`applicationContext as BasculeApplication`), which is exactly
 * why the decision is a pure function rather than inline `when` arms.
 */
class CooldownDispositionTest {

    /**
     * The case that matters most, and the one a release hooked into the
     * outcome mapper would have missed entirely: the advertisement went stale
     * waiting for a dispatch slot, so nothing touched the radio and the next
     * advertisement — carrying a fresh `seenAt` — is expected to succeed.
     */
    @Test
    fun aStaleAdvertisementReleasesTheAddressImmediately() {
        assertEquals(
            CooldownDisposition.RELEASE,
            cooldownDispositionFor(SessionExitReason.STALE_ADVERTISEMENT),
        )
    }

    @Test
    fun aCapturedReadingHoldsTheFullWindow() {
        assertEquals(CooldownDisposition.HOLD, cooldownDispositionFor(SessionExitReason.CAPTURED))
    }

    /** Terminal for this device: retrying cannot make it a compatible scale. */
    @Test
    fun anIncompatibleDeviceHoldsTheFullWindow() {
        assertEquals(CooldownDisposition.HOLD, cooldownDispositionFor(SessionExitReason.INCOMPATIBLE))
    }

    /**
     * The user-visible half: a session that reached the radio and came back
     * empty must be retryable in seconds, not minutes.
     */
    @Test
    fun aSessionThatMissedTheReadingBacksOffRatherThanHolding() {
        assertEquals(CooldownDisposition.BACKOFF, cooldownDispositionFor(SessionExitReason.MISSED))
    }

    @Test
    fun aRefusedForegroundStartBacksOffRatherThanHolding() {
        assertEquals(
            CooldownDisposition.BACKOFF,
            cooldownDispositionFor(SessionExitReason.FOREGROUND_REFUSED),
        )
    }

    /**
     * The back door the `finally` in `doWork` exists to shut: a session that
     * threw or was cancelled must still release its hold, or an unclassified
     * failure reinstates the five-minute lockout without ever being named.
     */
    @Test
    fun anUnexpectedFailureBacksOffRatherThanHolding() {
        assertEquals(
            CooldownDisposition.BACKOFF,
            cooldownDispositionFor(SessionExitReason.UNEXPECTED_ERROR),
        )
    }

    /**
     * A tripwire, not a restatement: every exit must be classified, and the two
     * treatments that can cost a weigh-in — holding for five minutes — stay
     * restricted to the two reasons that genuinely earn it. A new
     * [SessionExitReason] added without thought fails here rather than silently
     * inheriting a five-minute lockout.
     */
    @Test
    fun onlyACaptureOrAnIncompatibleDeviceEverHoldsTheFullWindow() {
        val holding = SessionExitReason.entries
            .filter { cooldownDispositionFor(it) == CooldownDisposition.HOLD }
            .toSet()

        assertEquals(
            setOf(SessionExitReason.CAPTURED, SessionExitReason.INCOMPATIBLE),
            holding,
        )
    }

    /**
     * Releasing is safe only where a retry cannot spin: every other exit
     * describes a condition still true a millisecond later, so releasing it
     * would reconnect on every packet of a 2-10/s advertisement burst.
     */
    @Test
    fun onlyAStaleAdvertisementIsEverReleased() {
        val released = SessionExitReason.entries
            .filter { cooldownDispositionFor(it) == CooldownDisposition.RELEASE }
            .toSet()

        assertEquals(setOf(SessionExitReason.STALE_ADVERTISEMENT), released)
    }
}
