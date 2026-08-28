package com.ventouxlabs.bascule.network

import com.ventouxlabs.bascule.data.WeightUnit
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L5: the v2 shape, tested directly rather than through `VitalForgeHttpClient`.
 * `putOptional`'s null-skip decides both the wire body *and* `deliveredFields`,
 * which is what a later contract upgrade reads to know what was never sent —
 * a shaper that emitted a key but forgot the field (or vice versa) would look
 * identical through a socket-level test that only inspects the body.
 */
class V2ShaperTest {

    /** [ReadingFixtures.captured] leaves `boneMassKg` and `amr` null; this is the every-field-present row. */
    private val complete = ReadingFixtures.captured().copy(boneMassKg = 3.1, amr = 2100.0)

    @Test
    fun bodyCarriesEveryFieldWhenNoneAreNull() {
        val payload = V2Shaper.shape(complete, WeightUnit.KILOGRAMS)

        assertEquals(
            setOf(
                "weight", "unit", "captured_at", "client_id",
                "body_fat_pct", "body_water_pct", "muscle_pct", "bone_mass_kg",
                "bmi", "bmr", "amr",
            ),
            payload.json.keys,
        )
    }

    @Test
    fun weightIsConvertedFromCanonicalKilogramsToDisplayUnit() {
        val kg = V2Shaper.shape(complete, WeightUnit.KILOGRAMS)
        val lbs = V2Shaper.shape(complete, WeightUnit.POUNDS)

        assertEquals(90.82, kg.json.getValue("weight").jsonPrimitive.doubleOrNull!!, 1e-9)
        assertEquals("kg", kg.json.getValue("unit").jsonPrimitive.content)
        assertEquals(200.22, lbs.json.getValue("weight").jsonPrimitive.doubleOrNull!!, 0.01)
        assertEquals("lbs", lbs.json.getValue("unit").jsonPrimitive.content)
    }

    /**
     * Unlike v1, v2 sends `client_id` — it is the server's idempotency key, so
     * it must be the row's own id and not a per-attempt value.
     */
    @Test
    fun sendsTheRowIdAsClientIdAndTheCaptureTimestamp() {
        val payload = V2Shaper.shape(complete, WeightUnit.KILOGRAMS)

        assertEquals(complete.id, payload.json.getValue("client_id").jsonPrimitive.content)
        assertEquals(
            ReadingFixtures.CAPTURED_AT_MILLIS,
            payload.json.getValue("captured_at").jsonPrimitive.longOrNull,
        )
    }

    @Test
    fun deliveredFieldsMatchesTheContractExactlyWhenEveryFieldIsPresent() {
        val payload = V2Shaper.shape(complete, WeightUnit.KILOGRAMS)

        assertEquals(
            "deliveredFields is derived from the shaper that ran, so it cannot drift from the wire",
            ContractVersion.V2_BODY_COMP.supportedFields,
            payload.fields,
        )
    }

    // --- putOptional: a null body-composition field is omitted, not sent as JSON null.

    @Test
    fun everyNullOptionalIsOmittedFromTheBody() {
        val bare = complete.copy(
            bodyFatPct = null,
            bodyWaterPct = null,
            musclePct = null,
            boneMassKg = null,
            bmi = null,
            bmr = null,
            amr = null,
        )

        val payload = V2Shaper.shape(bare, WeightUnit.KILOGRAMS)

        assertEquals(
            "a null must be absent, not an explicit JSON null — which can overwrite a value the server already has",
            setOf("weight", "unit", "captured_at", "client_id"),
            payload.json.keys,
        )
    }

    @Test
    fun everyNullOptionalIsAlsoAbsentFromDeliveredFields() {
        val bare = complete.copy(
            bodyFatPct = null,
            bodyWaterPct = null,
            musclePct = null,
            boneMassKg = null,
            bmi = null,
            bmr = null,
            amr = null,
        )

        assertEquals(
            setOf(ReadingField.WEIGHT, ReadingField.CAPTURED_AT),
            V2Shaper.shape(bare, WeightUnit.KILOGRAMS).fields,
        )
    }

    /**
     * The mixed case is the one a body-composition scale actually produces —
     * the BF720 fixture reports fat and water but never bone mass or AMR. An
     * all-or-nothing skip would pass both tests above and still be wrong here.
     */
    @Test
    fun aPartiallyPopulatedReadingKeepsOnlyItsNonNullFields() {
        val payload = V2Shaper.shape(ReadingFixtures.captured(), WeightUnit.KILOGRAMS)

        assertTrue("bone_mass_kg" !in payload.json.keys)
        assertTrue("amr" !in payload.json.keys)
        assertEquals(42.2, payload.json.getValue("body_fat_pct").jsonPrimitive.doubleOrNull!!, 1e-9)
        assertEquals(
            setOf(
                ReadingField.WEIGHT,
                ReadingField.CAPTURED_AT,
                ReadingField.BODY_FAT_PCT,
                ReadingField.BODY_WATER_PCT,
                ReadingField.MUSCLE_PCT,
                ReadingField.BMI,
                ReadingField.BMR,
            ),
            payload.fields,
        )
    }

    /**
     * `impedanceOhms` and `softLeanMassKg` are stored but have no
     * [ReadingField], so no contract version can claim to deliver them — they
     * must never reach the wire under a name invented here (00-design.md §4.2
     * pins v2's keys to VitalForge's own contract doc).
     */
    @Test
    fun neverSendsFieldsThatHaveNoContractName() {
        val payload = V2Shaper.shape(complete, WeightUnit.KILOGRAMS)

        assertFalse("impedance_ohms" in payload.json.keys)
        assertFalse("soft_lean_mass_kg" in payload.json.keys)
    }
}
