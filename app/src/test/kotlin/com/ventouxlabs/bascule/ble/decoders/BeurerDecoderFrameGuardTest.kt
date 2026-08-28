package com.ventouxlabs.bascule.ble.decoders

import com.ventouxlabs.bascule.ble.fake.Bf720Capture
import com.ventouxlabs.bascule.ble.session.DecodeEvent
import com.ventouxlabs.bascule.ble.session.DiscoveredServices
import com.ventouxlabs.bascule.ble.session.SessionBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on frames the BF720 never sent during the capture but the protocol
 * allows: a body-composition frame stranded past its correlation window, a
 * multi-packet segment, an unset device clock, and a truncated User Control
 * Point response.
 */
class BeurerDecoderFrameGuardTest {

    private var now = 0L
    private val decoder = BeurerDecoder(clock = { now })

    private val correlationWindowMillis =
        SessionBudget.BODY_COMPOSITION_CORRELATION_WINDOW.inWholeMilliseconds

    private fun weight() = decoder.onNotification(
        SigWeightProfile.WEIGHT_MEASUREMENT,
        Bf720Capture.WEIGHT_MEASUREMENT,
    )

    private fun bodyComposition() = decoder.onNotification(
        SigWeightProfile.BODY_COMPOSITION_MEASUREMENT,
        Bf720Capture.BODY_COMPOSITION_MEASUREMENT,
    )

    @Test
    fun anOrphanBodyCompositionPairsWithAWeightInsideTheCorrelationWindow() {
        assertEquals(DecodeEvent.Ignored, bodyComposition())

        now = correlationWindowMillis
        val event = weight()

        assertTrue("expected the pair to complete, got $event", event is DecodeEvent.Stable)
        assertEquals(
            Bf720Capture.EXPECTED_BODY_FAT_PCT,
            (event as DecodeEvent.Stable).reading.bodyFatPct ?: 0.0,
            TOLERANCE,
        )
        assertEquals(0, decoder.unpairableFramesDropped)
    }

    /**
     * The regression this guards: an orphan used to be held for the whole 45 s
     * first-indication budget and merged into whatever weight frame came next.
     * On a shared scale that is one household member's body composition written
     * into another's row.
     */
    @Test
    fun anOrphanBodyCompositionIsDroppedRatherThanPairedWithALaterWeighIn() {
        assertEquals(DecodeEvent.Ignored, bodyComposition())

        now = correlationWindowMillis + 1
        assertEquals(
            "past its window the orphan is gone, so the weight is merely buffered",
            DecodeEvent.Ignored,
            weight(),
        )
        assertEquals(1, decoder.unpairableFramesDropped)

        val flushed = decoder.flush() as DecodeEvent.Stable
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, flushed.reading.weightKg, TOLERANCE)
        assertNull("the stale frame must not have been attached", flushed.reading.bodyFatPct)
        assertNull(flushed.reading.impedanceOhms)
    }

    @Test
    fun aSecondOrphanCountsTheOneItReplacesAsDropped() {
        assertEquals(DecodeEvent.Ignored, bodyComposition())
        assertEquals(DecodeEvent.Ignored, bodyComposition())

        assertEquals(
            "the orphan that can no longer be paired must be counted, not silently lost",
            1,
            decoder.unpairableFramesDropped,
        )
        assertTrue(weight() is DecodeEvent.Stable)
    }

    @Test
    fun anOrphanNamingAnotherUserIsNotPairedWithThisWeighIn() {
        assertEquals(
            DecodeEvent.Ignored,
            decoder.onNotification(
                SigWeightProfile.BODY_COMPOSITION_MEASUREMENT,
                BODY_COMPOSITION_FOR_ANOTHER_USER,
            ),
        )

        assertEquals(
            "the captured weight frame is user 2, the orphan says user 7",
            DecodeEvent.Ignored,
            weight(),
        )
        assertEquals(1, decoder.unpairableFramesDropped)

        val flushed = decoder.flush() as DecodeEvent.Stable
        assertNull(flushed.reading.bodyFatPct)
    }

    @Test
    fun aMultiPacketSegmentIsRejectedRatherThanParsedAsACompleteMeasurement() {
        val segment = Bf720Capture.BODY_COMPOSITION_MEASUREMENT.copyOf().also {
            it[FLAGS_HIGH_BYTE_OFFSET] = (it[FLAGS_HIGH_BYTE_OFFSET].toInt() or MULTI_PACKET_HIGH_BIT).toByte()
        }

        assertNull(
            "this decoder cannot reassemble segments, so a segment is not a measurement",
            BodyCompositionMeasurementParser.parse(segment),
        )

        val event = decoder.onNotification(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT, segment)
        assertTrue("expected Malformed, got $event", event is DecodeEvent.Malformed)
        assertEquals(1, decoder.malformedCount)

        assertEquals("nothing may have been buffered from it", DecodeEvent.Ignored, weight())
    }

    @Test
    fun anOutOfRangeDateTimeIsRejectedRatherThanRolledOverIntoTheFarFuture() {
        val unsetClock = Bf720Capture.WEIGHT_MEASUREMENT.copyOf().also {
            it[YEAR_OFFSET] = 0xFF.toByte()
            it[YEAR_OFFSET + 1] = 0xFF.toByte()
            it[MONTH_OFFSET] = 0xFF.toByte()
            it[DAY_OFFSET] = 0xFF.toByte()
        }

        val parsed = WeightMeasurementParser.parse(unsetClock) as WeightParseResult.Parsed

        assertNull("year 65535 is not a date", parsed.measurement.timestampMillis)
        assertEquals(
            "the seven bytes were still consumed, so nothing behind them shifted",
            Bf720Capture.EXPECTED_USER_INDEX,
            parsed.measurement.userIndex,
        )
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, parsed.measurement.weightKg, TOLERANCE)
    }

    /**
     * A success response with no user index is unusable, but the scale refused
     * nothing — flagging it as a rejected registration inflates the E19 counter
     * and tells the user their scale turned them down.
     */
    @Test
    fun aTruncatedRegistrationSuccessAbortsWithoutClaimingRegistrationWasRefused() {
        decoder.beginHandshake(discovered, HandshakeContext(null, freshConsentCode = 0x1234))

        val event = decoder.onNotification(
            SigWeightProfile.USER_CONTROL_POINT,
            TRUNCATED_REGISTRATION_SUCCESS,
        )
        val directive = decoder.onHandshakeEvent(event)

        assertTrue("expected an Abort, got $directive", directive is HandshakeDirective.Abort)
        assertFalse(
            "a truncated success is not a refusal",
            (directive as HandshakeDirective.Abort).registrationRejected,
        )
    }

    @Test
    fun anExplicitRegistrationRefusalIsStillFlaggedAsOne() {
        decoder.beginHandshake(discovered, HandshakeContext(null, freshConsentCode = 0x1234))

        val directive = decoder.onHandshakeEvent(
            decoder.onNotification(SigWeightProfile.USER_CONTROL_POINT, Bf720Capture.registrationFailure()),
        )

        assertTrue((directive as HandshakeDirective.Abort).registrationRejected)
    }

    private val discovered = DiscoveredServices(
        mapOf(
            SigWeightProfile.USER_DATA_SERVICE to setOf(SigWeightProfile.USER_CONTROL_POINT),
            SigWeightProfile.WEIGHT_SCALE_SERVICE to setOf(SigWeightProfile.WEIGHT_MEASUREMENT),
            SigWeightProfile.BODY_COMPOSITION_SERVICE to
                setOf(SigWeightProfile.BODY_COMPOSITION_MEASUREMENT),
        ),
    )

    private companion object {
        const val TOLERANCE = 1e-6

        /** Flags `0x0004` (user ID present only), 42.2 % body fat, user 7. */
        val BODY_COMPOSITION_FOR_ANOTHER_USER =
            byteArrayOf(0x04, 0x00, 0xa6.toByte(), 0x01, 0x07)

        /** Response to Register New User: success, but no user index byte. */
        val TRUNCATED_REGISTRATION_SUCCESS = byteArrayOf(0x20, 0x01, 0x01)

        /** Bit 12 of the body-composition flags word lives in its high byte. */
        const val FLAGS_HIGH_BYTE_OFFSET = 1
        const val MULTI_PACKET_HIGH_BIT = 0x10

        /** Offsets into the captured weight frame, whose flags are `0x0e`. */
        const val YEAR_OFFSET = 3
        const val MONTH_OFFSET = 5
        const val DAY_OFFSET = 6
    }
}
