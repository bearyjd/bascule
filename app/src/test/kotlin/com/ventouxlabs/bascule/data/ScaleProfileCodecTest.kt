package com.ventouxlabs.bascule.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleProfileCodecTest {

    private fun profile(
        id: String = "id-1",
        deviceAddress: String = "AA:BB:CC:DD:EE:FF",
        scaleIndex: Int = 1,
        consentCode: Int = 4321,
        active: Boolean = true,
    ) = ScaleProfile(
        id = id,
        deviceAddress = deviceAddress,
        scaleIndex = scaleIndex,
        consentCode = consentCode,
        label = "Profile $scaleIndex",
        registeredAtMillis = 1_000L,
        active = active,
        lastVerifiedAtMillis = 2_000L,
        initializationIncomplete = false,
    )

    @Test
    fun encodeThenDecodeRoundTripsEveryField() {
        val original = listOf(profile())
        val decoded = ScaleProfileCodec.decode(ScaleProfileCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun encodeToStringThenDecodeFromStringRoundTrips() {
        val original = listOf(
            profile(id = "id-1", active = true),
            profile(id = "id-2", scaleIndex = 2, active = false),
        )
        val decoded = ScaleProfileCodec.decodeFromString(ScaleProfileCodec.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun decodeDefaultsMissingOptionalFields() {
        val json = """[{"id":"id-1","address":"AA:BB:CC:DD:EE:FF","index":1,"code":4321,""" +
            """"label":"Profile 1","registered":1000,"active":true}]"""
        val decoded = ScaleProfileCodec.decodeFromString(json)
        assertNull(decoded.single().lastVerifiedAtMillis)
        assertEquals(false, decoded.single().initializationIncomplete)
    }

    @Test
    fun upsertOfANewActiveProfileDeactivatesEveryOtherProfile() {
        val current = listOf(
            profile(id = "id-1", active = true),
            profile(id = "id-2", scaleIndex = 2, active = false),
        )
        val incoming = profile(id = "id-3", scaleIndex = 3, active = true)
        val next = ScaleProfileCodec.upsertEnforcingSingleActive(current, incoming)
        assertEquals(1, next.count { it.active })
        assertTrue(next.single { it.active }.id == "id-3")
    }

    @Test
    fun upsertReplacesAnExistingProfileByIdRatherThanDuplicatingIt() {
        val current = listOf(profile(id = "id-1", consentCode = 1, active = true))
        val incoming = profile(id = "id-1", consentCode = 2, active = true)
        val next = ScaleProfileCodec.upsertEnforcingSingleActive(current, incoming)
        assertEquals(1, next.size)
        assertEquals(2, next.single().consentCode)
    }

    @Test
    fun upsertOfAnInactiveProfileLeavesTheExistingActiveProfileUntouched() {
        val current = listOf(profile(id = "id-1", active = true))
        val incoming = profile(id = "id-2", scaleIndex = 2, active = false)
        val next = ScaleProfileCodec.upsertEnforcingSingleActive(current, incoming)
        assertEquals(1, next.count { it.active })
        assertTrue(next.single { it.active }.id == "id-1")
    }
}
