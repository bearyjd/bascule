package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.diagnostics.CaptureOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The user-facing half of the same funnel [CooldownDispositionTest] covers.
 * Separate from it on purpose: the two functions answer different questions and
 * group the exits differently, and a test that asserted they agreed would be
 * asserting a coincidence.
 */
class CaptureOutcomeMappingTest {

    @Test
    fun aCapturedReadingReadsAsCaptured() {
        assertEquals(CaptureOutcome.CAPTURED, captureOutcomeFor(SessionExitReason.CAPTURED))
    }

    /**
     * The distinction that makes the line worth showing at all: this is the
     * phone's scheduler dropping the attempt, not the scale misbehaving, so it
     * must not read as a scale fault the user would try to fix by re-pairing.
     */
    @Test
    fun aStaleAdvertisementReadsAsThePhoneMissingItsWindow() {
        assertEquals(
            CaptureOutcome.MISSED_THE_WINDOW,
            captureOutcomeFor(SessionExitReason.STALE_ADVERTISEMENT),
        )
    }

    @Test
    fun aRefusedForegroundStartAlsoReadsAsThePhoneMissingItsWindow() {
        assertEquals(
            CaptureOutcome.MISSED_THE_WINDOW,
            captureOutcomeFor(SessionExitReason.FOREGROUND_REFUSED),
        )
    }

    /**
     * A refused foreground start and a stale advertisement share a user-facing
     * outcome but not a cooldown treatment — the divergence the two mapping
     * functions exist to allow, pinned so a later "simplification" into one
     * function fails here.
     */
    @Test
    fun theTwoMappingsAreAllowedToDisagreeAndDo() {
        val sameOutcome = captureOutcomeFor(SessionExitReason.STALE_ADVERTISEMENT) ==
            captureOutcomeFor(SessionExitReason.FOREGROUND_REFUSED)
        val sameDisposition = cooldownDispositionFor(SessionExitReason.STALE_ADVERTISEMENT) ==
            cooldownDispositionFor(SessionExitReason.FOREGROUND_REFUSED)

        assertEquals(true, sameOutcome)
        assertEquals(false, sameDisposition)
    }

    @Test
    fun reachingTheScaleAndGettingNothingReadsAsNoReading() {
        listOf(
            SessionExitReason.MISSED,
            SessionExitReason.DECODE_FAILURE,
            SessionExitReason.HANDSHAKE_FAILED,
            SessionExitReason.COMPLETED_WITHOUT_READING,
        ).forEach {
            assertEquals(
                "$it must read as a reached-but-empty session",
                CaptureOutcome.NO_READING,
                captureOutcomeFor(it),
            )
        }
    }

    @Test
    fun setupProblemsReadAsNotReady() {
        listOf(
            SessionExitReason.PERMISSION_DENIED,
            SessionExitReason.NO_ADAPTER,
            SessionExitReason.ADAPTER_DISABLED,
            SessionExitReason.NOT_ACTIVE_PROFILE,
            SessionExitReason.UNRESOLVABLE_DEVICE,
        ).forEach {
            assertEquals("$it must read as a setup problem", CaptureOutcome.NOT_READY, captureOutcomeFor(it))
        }
    }

    /**
     * A tripwire for the same reason its cooldown twin is one: a new exit added
     * without a user-facing story would otherwise inherit whatever the `when`
     * happened to fall through to.
     */
    @Test
    fun everyExitHasAUserFacingOutcome() {
        SessionExitReason.entries.forEach { captureOutcomeFor(it) }
    }

    /** Only a real capture may ever read as success. */
    @Test
    fun nothingButACaptureReadsAsCaptured() {
        val captured = SessionExitReason.entries.filter { captureOutcomeFor(it) == CaptureOutcome.CAPTURED }

        assertEquals(listOf(SessionExitReason.CAPTURED), captured)
    }
}
