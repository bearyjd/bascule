package com.ventouxlabs.bascule.ble.decoders

import com.ventouxlabs.bascule.ble.fake.Bf720Capture
import com.ventouxlabs.bascule.ble.session.DecodeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the revised decoder against the real captured frames.
 *
 * The property under test is the one ADR-007 forced: a Weight Measurement and
 * the Body Composition Measurement that follows it are ONE reading, and the
 * decoder must emit exactly one [DecodeEvent.Stable] carrying both.
 */
class BeurerDecoderCaptureTest {

    private val fixedClock = 1_700_000_000_000L
    private val decoder = BeurerDecoder(clock = { fixedClock })

    private fun weight() = decoder.onNotification(
        SigWeightProfile.WEIGHT_MEASUREMENT,
        Bf720Capture.WEIGHT_MEASUREMENT,
    )

    private fun bodyComposition() = decoder.onNotification(
        SigWeightProfile.BODY_COMPOSITION_MEASUREMENT,
        Bf720Capture.BODY_COMPOSITION_MEASUREMENT,
    )

    /** A second household member stepping on during the same BLE session. */
    private fun secondUserWeight() = decoder.onNotification(
        SigWeightProfile.WEIGHT_MEASUREMENT,
        Bf720Capture.weightMeasurementForUser(
            userIndex = OTHER_USER_INDEX,
            rawWeight = OTHER_USER_RAW_WEIGHT,
        ),
    )

    @Test
    fun weightFrameAloneIsNotYetAReading() {
        assertEquals(
            "a weight frame is buffered for correlation, not emitted on its own",
            DecodeEvent.Ignored,
            weight(),
        )
    }

    @Test
    fun pairedFramesProduceExactlyOneStableReading() {
        weight()
        val event = bodyComposition()

        assertTrue("expected Stable, got $event", event is DecodeEvent.Stable)
        assertNull("nothing may remain buffered after a complete pair", decoder.flush())
    }

    @Test
    fun stableReadingCarriesTheWeightFramesAttribution() {
        weight()
        val reading = (bodyComposition() as DecodeEvent.Stable).reading

        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
        assertEquals(Bf720Capture.EXPECTED_USER_INDEX, reading.userIndex)
        assertEquals(Bf720Capture.expectedTimestampMillis, reading.scaleTimestampMillis)
        assertEquals(Bf720Capture.EXPECTED_BMI, reading.bmi!!, TOLERANCE)
        assertEquals(Bf720Capture.EXPECTED_HEIGHT_M, reading.heightM!!, TOLERANCE)
        assertEquals(fixedClock, reading.capturedAtMillis)
        assertEquals(BeurerDecoder.DECODER_ID, reading.decoderId)
    }

    @Test
    fun stableReadingCarriesTheBodyCompositionFields() {
        weight()
        val reading = (bodyComposition() as DecodeEvent.Stable).reading

        assertEquals(Bf720Capture.EXPECTED_BODY_FAT_PCT, reading.bodyFatPct!!, TOLERANCE)
        assertEquals(Bf720Capture.EXPECTED_MUSCLE_PCT, reading.musclePct!!, TOLERANCE)
        assertEquals(
            Bf720Capture.EXPECTED_BASAL_METABOLISM_KJ,
            reading.basalMetabolismKj!!,
            TOLERANCE,
        )
        assertEquals(
            Bf720Capture.EXPECTED_SOFT_LEAN_MASS_KG,
            reading.softLeanMassKg!!,
            TOLERANCE,
        )
        assertEquals(
            Bf720Capture.EXPECTED_BODY_WATER_MASS_KG,
            reading.bodyWaterMassKg!!,
            TOLERANCE,
        )
        assertEquals(Bf720Capture.EXPECTED_IMPEDANCE_OHMS, reading.impedanceOhms!!, TOLERANCE)
    }

    @Test
    fun fieldsTheUnitDoesNotSupportStayNull() {
        weight()
        val reading = (bodyComposition() as DecodeEvent.Stable).reading

        // The BF720's Body Composition Feature (0x2A9B = cf 31 00 00) reports
        // muscle mass and fat-free mass as unsupported, and its frame flags say
        // so — decoding must not invent them.
        assertNull(reading.muscleMassKg)
        assertNull(reading.fatFreeMassKg)
        // Neither is a field of the SIG Body Composition profile at all.
        assertNull(reading.boneMassKg)
        assertNull(reading.amr)
    }

    @Test
    fun bodyCompositionFrameCarriesNoAttributionOfItsOwn() {
        val parsed = BodyCompositionMeasurementParser.parse(
            Bf720Capture.BODY_COMPOSITION_MEASUREMENT,
        )!!

        // This is the whole reason correlation exists (ADR-007).
        assertNull(parsed.userIndex)
        assertNull(parsed.timestampMillis)
    }

    @Test
    fun bodyCompositionArrivingBeforeItsWeightFrameStillPairs() {
        assertEquals(
            "an orphan body-composition frame is held, not emitted",
            DecodeEvent.Ignored,
            bodyComposition(),
        )

        val event = weight()
        assertTrue("expected the pair to complete on the weight frame", event is DecodeEvent.Stable)
        val reading = (event as DecodeEvent.Stable).reading
        assertEquals(Bf720Capture.EXPECTED_USER_INDEX, reading.userIndex)
        assertEquals(Bf720Capture.EXPECTED_BODY_FAT_PCT, reading.bodyFatPct!!, TOLERANCE)
    }

    @Test
    fun repeatedWeightFrameIsSuppressedNotEmittedTwice() {
        weight()
        bodyComposition()

        assertEquals(
            "a re-broadcast of an already-emitted frame must not produce a second reading",
            DecodeEvent.Ignored,
            weight(),
        )
        assertEquals(1, decoder.duplicateFramesSuppressed)
        assertNull(decoder.flush())
    }

    @Test
    fun weightFrameWithNoBodyCompositionIsReleasedOnFlush() {
        weight()

        val flushed = decoder.flush()
        assertTrue("a weigh-in without body comp is still a reading", flushed is DecodeEvent.Stable)
        val reading = (flushed as DecodeEvent.Stable).reading
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, reading.weightKg, TOLERANCE)
        assertNull(reading.bodyFatPct)
        assertNull("flush is not repeatable", decoder.flush())
    }

    @Test
    fun truncatedWeightFrameIsMalformedAndNeverThrows() {
        val event = decoder.onNotification(
            SigWeightProfile.WEIGHT_MEASUREMENT,
            Bf720Capture.WEIGHT_MEASUREMENT.copyOf(6),
        )

        assertTrue(event is DecodeEvent.Malformed)
        assertEquals(6, (event as DecodeEvent.Malformed).length)
        assertEquals(1, decoder.malformedCount)
    }

    @Test
    fun lateBodyCompositionAfterSupersededWeightIsDroppedNotMisattributed() {
        // W1 — JD's weigh-in, buffered awaiting its body-composition pair.
        assertEquals(DecodeEvent.Ignored, weight())

        // W2 — a second household member, same session. W1's pair is now
        // unprovable, so W1 is released weight-only and correlation closes.
        val superseding = secondUserWeight()
        assertTrue("expected W1 released weight-only, got $superseding", superseding is DecodeEvent.Stable)
        val released = (superseding as DecodeEvent.Stable).reading
        assertEquals(Bf720Capture.EXPECTED_USER_INDEX, released.userIndex)
        assertEquals(Bf720Capture.EXPECTED_WEIGHT_KG, released.weightKg, TOLERANCE)
        assertNull("a released weight carries no body composition", released.bodyFatPct)
        assertNull(released.impedanceOhms)
        assertNull(released.softLeanMassKg)

        // BC1 — W1's body composition, arriving late. It names neither a user nor
        // a weigh-in, so pairing it with whatever is pending would write one
        // person's body composition into the other's row.
        assertEquals(
            "a body-composition frame that can no longer be paired is dropped",
            DecodeEvent.Ignored,
            bodyComposition(),
        )

        // Exactly one reading for the whole sequence, and none corrected after
        // the fact: the released row is the only thing this session ever emitted.
        assertNull("nothing is emitted retroactively", decoder.flush())
        assertEquals(2, decoder.unpairableFramesDropped)
    }

    @Test
    fun bodyCompositionAfterACompletedPairIsDroppedNotHeldForTheNextWeight() {
        weight()
        assertTrue(bodyComposition() is DecodeEvent.Stable)

        // A repeated body-composition frame must not be buffered as an orphan:
        // the next weight frame would inherit the previous weigh-in's fields.
        assertEquals(DecodeEvent.Ignored, bodyComposition())
        assertEquals(
            "correlation is closed for the rest of the session",
            DecodeEvent.Ignored,
            secondUserWeight(),
        )
        assertEquals(2, decoder.unpairableFramesDropped)
        assertNull(decoder.flush())
    }

    private companion object {
        const val TOLERANCE = 1e-6

        /** Another household member: index 5, 15 000 × 0.005 kg = 75.00 kg. */
        const val OTHER_USER_INDEX = 5
        const val OTHER_USER_RAW_WEIGHT = 15_000
    }
}
