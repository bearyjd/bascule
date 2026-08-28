package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.ble.session.ScaleCredential
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
    )

    // --- TS-H3: an unreadable stored blob must never be mistaken for an empty registry.

    @Test
    fun readStoredReportsAbsentOnlyWhenNothingHasEverBeenWritten() {
        assertEquals(ScaleProfileCodec.StoredProfiles.Absent, ScaleProfileCodec.readStored(null))
    }

    @Test
    fun readStoredParsesAWellFormedBlob() {
        val stored = ScaleProfileCodec.readStored(ScaleProfileCodec.encodeToString(listOf(profile())))

        val parsed = stored as ScaleProfileCodec.StoredProfiles.Parsed
        assertEquals(listOf(profile()), parsed.profiles)
    }

    /**
     * The live TS-H3 scenario: a validation rule added after data was stored.
     * The blob is intact and the address is simply one this build now rejects;
     * reporting an empty registry would let the next write erase it.
     */
    @Test
    fun readStoredReportsUnreadableForAnAddressThisBuildNowRejects() {
        val blob = ScaleProfileCodec.encodeToString(listOf(profile()))
            .replace("AA:BB:CC:DD:EE:FF", "AA-BB-CC-DD-EE-FF")

        val stored = ScaleProfileCodec.readStored(blob)

        assertEquals(blob, (stored as ScaleProfileCodec.StoredProfiles.Unreadable).raw)
    }

    @Test
    fun readStoredReportsUnreadableForEveryShapeFailureRatherThanThrowing() {
        val blobs = listOf(
            "not json at all",
            "{}",
            "[42]",
            """[{"id":"a"}]""",
            ScaleProfileCodec.encodeToString(listOf(profile()))
                .replace(""""registered":1000""", """"registered":"x""""),
        )

        blobs.forEach { blob ->
            assertTrue(
                "must classify rather than throw: $blob",
                ScaleProfileCodec.readStored(blob) is ScaleProfileCodec.StoredProfiles.Unreadable,
            )
        }
    }

    // --- S1: a decoded profile comes from an imported backup file, so decode is a trust boundary.

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsAMalformedDeviceAddress() {
        val json = ScaleProfileCodec.encodeToString(listOf(profile()))
            .replace("AA:BB:CC:DD:EE:FF", "not-an-address")
        ScaleProfileCodec.decodeFromString(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsAnOutOfRangeScaleIndex() {
        val json = ScaleProfileCodec.encodeToString(listOf(profile(scaleIndex = 1)))
            .replace("\"index\":1", "\"index\":256")
        ScaleProfileCodec.decodeFromString(json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decodeRejectsAnOutOfRangeConsentCode() {
        val json = ScaleProfileCodec.encodeToString(listOf(profile(consentCode = 4321)))
            .replace("\"code\":4321", "\"code\":65536")
        ScaleProfileCodec.decodeFromString(json)
    }

    @Test
    fun decodeNormalisesAddressCaseSoComparisonsAreStable() {
        val json = ScaleProfileCodec.encodeToString(listOf(profile()))
            .replace("AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff")
        assertEquals("AA:BB:CC:DD:EE:FF", ScaleProfileCodec.decodeFromString(json).single().deviceAddress)
    }

    // --- H5: the migration rule, split out of credentialFor so a read never writes.

    @Test
    fun legacyMigrationProfileBuildsAnActiveProfileFromTheLegacyCredential() {
        val migrated = ScaleProfileCodec.legacyMigrationProfile(
            current = emptyList(),
            deviceAddress = "aa:bb:cc:dd:ee:ff",
            legacy = ScaleCredential(scaleIndex = 3, consentCode = 999),
            id = "new-id",
            nowMillis = 42L,
        )
        assertEquals("AA:BB:CC:DD:EE:FF", migrated?.deviceAddress)
        assertEquals(3, migrated?.scaleIndex)
        assertEquals(999, migrated?.consentCode)
        assertEquals(42L, migrated?.registeredAtMillis)
        assertTrue(migrated?.active == true)
    }

    @Test
    fun legacyMigrationProfileIsNullWhenAnActiveProfileAlreadyCoversTheAddress() {
        val existing = listOf(profile(deviceAddress = "AA:BB:CC:DD:EE:FF", active = true))
        assertNull(
            ScaleProfileCodec.legacyMigrationProfile(
                current = existing,
                deviceAddress = "aa:bb:cc:dd:ee:ff",
                legacy = ScaleCredential(scaleIndex = 3, consentCode = 999),
                id = "new-id",
                nowMillis = 42L,
            ),
        )
    }

    @Test
    fun legacyMigrationProfileIsNullWhenTheLegacyStoreHasNothing() {
        assertNull(
            ScaleProfileCodec.legacyMigrationProfile(
                current = emptyList(),
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                legacy = null,
                id = "new-id",
                nowMillis = 42L,
            ),
        )
    }

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
