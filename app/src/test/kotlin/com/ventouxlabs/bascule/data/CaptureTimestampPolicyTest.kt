package com.ventouxlabs.bascule.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `capturedAtMillis` means "when the weigh-in happened". A stored reading the
 * scale hands over on the next consent (#28) can be hours old by the time the
 * phone receives it, and only the scale's own timestamp knows when the person
 * actually stood on it.
 */
class CaptureTimestampPolicyTest {

    @Test
    fun aScaleTimestampInsideTheWindowIsTheCaptureTime() {
        val scaleTime = RECEIVED_AT - 3 * 60 * 60 * 1000L
        assertEquals(scaleTime, CaptureTimestampPolicy.resolve(scaleTime, RECEIVED_AT))
    }

    @Test
    fun aFrameWithNoTimestampFallsBackToTheReceivedTime() {
        assertEquals(RECEIVED_AT, CaptureTimestampPolicy.resolve(null, RECEIVED_AT))
    }

    @Test
    fun aScaleTimestampOlderThanTheWindowIsNotBelieved() {
        val tooOld = RECEIVED_AT - CaptureTimestampPolicy.MAX_PAST_MILLIS - 1
        assertEquals(RECEIVED_AT, CaptureTimestampPolicy.resolve(tooOld, RECEIVED_AT))
    }

    @Test
    fun aScaleTimestampTooFarInTheFutureIsNotBelieved() {
        val tooNew = RECEIVED_AT + CaptureTimestampPolicy.MAX_FUTURE_MILLIS + 1
        assertEquals(RECEIVED_AT, CaptureTimestampPolicy.resolve(tooNew, RECEIVED_AT))
    }

    @Test
    fun exactlyAtThePastBoundIsStillBelieved() {
        val atBound = RECEIVED_AT - CaptureTimestampPolicy.MAX_PAST_MILLIS
        assertEquals(atBound, CaptureTimestampPolicy.resolve(atBound, RECEIVED_AT))
    }

    @Test
    fun exactlyAtTheFutureBoundIsStillBelieved() {
        val atBound = RECEIVED_AT + CaptureTimestampPolicy.MAX_FUTURE_MILLIS
        assertEquals(atBound, CaptureTimestampPolicy.resolve(atBound, RECEIVED_AT))
    }

    /**
     * Pins the *value*, not just the constant: VitalForge rejects a
     * `captured_at` more than 60 s ahead of its own clock (422, permanent), so
     * the future bound has to sit well under that, and the received time is
     * the closer answer for any skew anyway.
     */
    @Test
    fun thirtyOneSecondsAheadIsSkewAndTheReceivedTimeWins() {
        assertEquals(RECEIVED_AT, CaptureTimestampPolicy.resolve(RECEIVED_AT + 31_000L, RECEIVED_AT))
    }

    /** The past bound exists to reject a reset RTC's default epoch, not a weigh-in from last month. */
    @Test
    fun aSixWeekOldWeighInIsStillBelieved() {
        val sixWeeksAgo = RECEIVED_AT - 42 * DAY_MILLIS
        assertEquals(sixWeeksAgo, CaptureTimestampPolicy.resolve(sixWeeksAgo, RECEIVED_AT))
    }

    /** `FrameReader` accepts years from 2000, so a scale that came up on 2000-01-01 decodes to a real instant. */
    @Test
    fun aResetClocksDefaultEpochIsNotBelieved() {
        assertEquals(RECEIVED_AT, CaptureTimestampPolicy.resolve(EPOCH_2000_01_01, RECEIVED_AT))
    }

    private companion object {
        /** A realistic epoch, so a bound arithmetic mistake cannot hide behind zero. */
        const val RECEIVED_AT = 1_787_000_000_000L

        const val DAY_MILLIS = 24 * 60 * 60 * 1000L

        /** 2000-01-01T00:00:00Z. */
        const val EPOCH_2000_01_01 = 946_684_800_000L
    }
}
