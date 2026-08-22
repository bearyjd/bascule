package com.ventouxlabs.bascule.network

import com.ventouxlabs.bascule.data.WeightUnit
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The v1 payload shape, tested directly rather than through a socket — the
 * shaper is a pure function and its most important property is an absence.
 */
class V1ShaperTest {

    private val reading = ReadingFixtures.captured()

    @Test
    fun bodyIsExactlyWeightAndUnit() {
        val payload = V1Shaper.shape(reading, WeightUnit.KILOGRAMS)

        // 00-design.md §4.1 pins the v1 body to these two keys. Against a Python
        // route with strict request validation an extra key is a 422, and §4.5
        // sends a 422 straight to FAILED_PERMANENT on the first attempt — total
        // data loss on the very first weigh-in.
        assertEquals(setOf("weight", "unit"), payload.json.keys)
    }

    @Test
    fun doesNotSendClientId() {
        assertFalse("client_id" in V1Shaper.shape(reading, WeightUnit.KILOGRAMS).json.keys)
    }

    @Test
    fun weightIsConvertedFromCanonicalKilogramsToDisplayUnit() {
        val kg = V1Shaper.shape(reading, WeightUnit.KILOGRAMS)
        val lbs = V1Shaper.shape(reading, WeightUnit.POUNDS)

        assertEquals(90.82, kg.json.getValue("weight").jsonPrimitive.doubleOrNull!!, 1e-9)
        assertEquals("kg", kg.json.getValue("unit").jsonPrimitive.content)
        assertEquals(200.22, lbs.json.getValue("weight").jsonPrimitive.doubleOrNull!!, 0.01)
        assertEquals("lbs", lbs.json.getValue("unit").jsonPrimitive.content)
    }

    @Test
    fun deliveredFieldsIsWeightOnly() {
        assertEquals(
            setOf(ReadingField.WEIGHT),
            V1Shaper.shape(reading, WeightUnit.KILOGRAMS).fields,
        )
    }

    @Test
    fun deliveredFieldsNeverExceedsWhatTheContractSupports() {
        val payload = V1Shaper.shape(reading, WeightUnit.KILOGRAMS)

        assertEquals(
            "deliveredFields is derived from the shaper that ran, so it cannot drift from the wire",
            ContractVersion.V1_WEIGHT_ONLY.supportedFields,
            payload.fields,
        )
    }
}
