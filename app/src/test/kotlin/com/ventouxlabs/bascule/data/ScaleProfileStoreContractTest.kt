package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.data.fake.FakeScaleProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The [ScaleProfileStore] behaviours that do not depend on encryption. The
 * production store wraps these same pure rules
 * ([ScaleProfileCodec.legacyMigrationProfile], [ScaleProfileCodec.requireWithinBounds])
 * around EncryptedSharedPreferences, which needs a real keystore — these
 * tests deliberately avoid that dependency rather than requiring one.
 * NOTE: this project has no `app/src/androidTest` tree, so the real
 * EncryptedSharedPreferences-backed path is not covered by any instrumented
 * test either; that is a known coverage gap, not something these tests substitute for.
 */
class ScaleProfileStoreContractTest {

    private val address = "AA:BB:CC:DD:EE:FF"

    private fun profile(
        id: String = "id-1",
        scaleIndex: Int = 1,
        consentCode: Int = 4321,
        active: Boolean = true,
    ) = ScaleProfile(
        id = id,
        deviceAddress = address,
        scaleIndex = scaleIndex,
        consentCode = consentCode,
        label = "Profile $scaleIndex",
        registeredAtMillis = 1_000L,
        active = active,
    )

    // --- H5: credentialFor reads; migrateLegacyCredential is the only writer.

    @Test
    fun credentialForReadsThroughToTheLegacyStoreWithoutMigrating() {
        val legacy = InMemoryConsentStore().apply { save(address, ScaleCredential(3, 999)) }
        val store = FakeScaleProfileStore(legacy = legacy)

        assertEquals(ScaleCredential(3, 999), store.credentialFor(address))
        assertTrue("reading a credential must not write a profile", store.profiles.value.isEmpty())
    }

    @Test
    fun credentialForPrefersTheActiveRegistryProfileOverTheLegacyStore() {
        val legacy = InMemoryConsentStore().apply { save(address, ScaleCredential(9, 111)) }
        val store = FakeScaleProfileStore(listOf(profile(scaleIndex = 1, consentCode = 4321)), legacy)

        assertEquals(ScaleCredential(1, 4321), store.credentialFor(address))
    }

    /**
     * M11: the registry answers for an inactive profile too. The handshake asks
     * "do we hold any credential for this device"; answering null for a
     * registered-but-inactive profile makes `GattSession` register again and
     * consume another of the scale's eight slots for a device that already has
     * one. Reading it still must not activate or write anything.
     */
    @Test
    fun credentialForAnswersFromAnInactiveProfileRatherThanRegisteringAgain() {
        val legacy = InMemoryConsentStore().apply { save(address, ScaleCredential(9, 111)) }
        val store = FakeScaleProfileStore(listOf(profile(active = false)), legacy)

        assertEquals(ScaleCredential(1, 4321), store.credentialFor(address))
        assertEquals("the read must not add or activate a profile", 1, store.profiles.value.size)
        assertNull(store.activeProfile.value)
    }

    @Test
    fun credentialForStillPrefersTheActiveProfileWhenBothExist() {
        val store = FakeScaleProfileStore(
            listOf(
                profile(id = "inactive", scaleIndex = 5, consentCode = 111, active = false),
                profile(id = "active", scaleIndex = 1, consentCode = 4321, active = true),
            ),
        )

        assertEquals(ScaleCredential(1, 4321), store.credentialFor(address))
    }

    @Test
    fun migrateLegacyCredentialPromotesTheLegacyEntryIntoTheRegistry() {
        val legacy = InMemoryConsentStore().apply { save(address, ScaleCredential(3, 999)) }
        val store = FakeScaleProfileStore(legacy = legacy)

        store.migrateLegacyCredential(address)

        val migrated = store.profiles.value.single()
        assertEquals(address, migrated.deviceAddress)
        assertEquals(3, migrated.scaleIndex)
        assertEquals(999, migrated.consentCode)
        assertTrue(migrated.active)
        assertEquals(migrated, store.activeProfile.value)
    }

    @Test
    fun migrateLegacyCredentialIsIdempotent() {
        val legacy = InMemoryConsentStore().apply { save(address, ScaleCredential(3, 999)) }
        val store = FakeScaleProfileStore(legacy = legacy)

        store.migrateLegacyCredential(address)
        store.migrateLegacyCredential(address)

        assertEquals(1, store.profiles.value.size)
    }

    @Test
    fun migrateLegacyCredentialDoesNothingWithoutALegacyEntry() {
        val store = FakeScaleProfileStore(legacy = InMemoryConsentStore())
        store.migrateLegacyCredential(address)
        assertTrue(store.profiles.value.isEmpty())
        assertNull(store.credentialFor(address))
    }

    // --- S9: the migrated-from copy must not be left behind.

    @Test
    fun migrateLegacyCredentialClearsTheLegacyEntryOnceTheProfileIsPersisted() {
        val legacy = InMemoryConsentStore().apply { save(address, ScaleCredential(3, 999)) }
        val store = FakeScaleProfileStore(legacy = legacy)

        store.migrateLegacyCredential(address)

        assertNull("the migrated-from copy must not survive", legacy.credentialFor(address))
        assertEquals(ScaleCredential(3, 999), store.credentialFor(address))
    }

    /**
     * The no-op branch fires when an active profile already covers the address —
     * possibly at a different scale index, so the legacy entry may be the only
     * copy of a distinct credential. Consent codes are not recoverable in-app.
     */
    @Test
    fun migrateLegacyCredentialLeavesTheLegacyEntryAloneWhenItDoesNotMigrate() {
        val legacy = InMemoryConsentStore().apply { save(address, ScaleCredential(7, 999)) }
        val store = FakeScaleProfileStore(listOf(profile(scaleIndex = 1)), legacy)

        store.migrateLegacyCredential(address)

        assertEquals(ScaleCredential(7, 999), legacy.credentialFor(address))
    }

    // --- S6: the consent code is credential material and never a log line.

    @Test
    fun profileToStringOmitsTheConsentCode() {
        val rendered = profile(consentCode = 41_207).toString()

        assertFalse("toString() must not render the consent code", rendered.contains("41207"))
        assertTrue("toString() should stay useful for debugging", rendered.contains(address))
    }

    // --- S1: replaceAll is the path an imported backup takes.

    @Test(expected = IllegalArgumentException::class)
    fun replaceAllRejectsAnOutOfRangeScaleIndex() {
        FakeScaleProfileStore().replaceAll(listOf(profile(scaleIndex = 256)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun replaceAllRejectsAnOutOfRangeConsentCode() {
        FakeScaleProfileStore().replaceAll(listOf(profile(consentCode = 65_536)))
    }

    @Test
    fun replaceAllAcceptsProfilesAtTheRangeBoundaries() {
        val store = FakeScaleProfileStore()
        store.replaceAll(
            listOf(
                profile(id = "low", scaleIndex = 0, consentCode = 0, active = true),
                profile(id = "high", scaleIndex = 255, consentCode = 65_535, active = false),
            ),
        )
        assertEquals(2, store.profiles.value.size)
    }

    /**
     * Two active profiles would make `activeProfile` depend on list order, and
     * that is what the scan filter is built from — so an imported backup
     * carrying two must be refused rather than silently resolved.
     */
    @Test(expected = IllegalArgumentException::class)
    fun replaceAllRejectsMoreThanOneActiveProfile() {
        FakeScaleProfileStore().replaceAll(
            listOf(
                profile(id = "first", scaleIndex = 1, active = true),
                profile(id = "second", scaleIndex = 2, active = true),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun replaceAllRejectsDuplicateIdsRatherThanChoosingWhichOneSurvives() {
        FakeScaleProfileStore().replaceAll(
            listOf(profile(id = "same", scaleIndex = 1), profile(id = "same", scaleIndex = 2, active = false)),
        )
    }

    /**
     * Deduping by first id would drop the active element here, so a list that
     * passes the single-active guard would still persist a registry with no
     * active profile at all — nothing to build a scan filter from, and no error
     * anywhere to say why capture stopped.
     */
    @Test
    fun replaceAllWithADuplicateIdWhoseSecondCopyIsActiveNeverLeavesZeroActiveProfiles() {
        val store = FakeScaleProfileStore(listOf(profile(id = "existing")))

        val result = runCatching {
            store.replaceAll(
                listOf(profile(id = "same", scaleIndex = 1, active = false), profile(id = "same", scaleIndex = 2)),
            )
        }

        assertTrue("the ambiguous list must be refused, not silently deduped", result.isFailure)
        assertEquals("the refusal must land before any write", "existing", store.activeProfile.value?.id)
    }

    /** Activating an id that is not in the registry would deactivate every profile and leave none armed. */
    @Test(expected = IllegalArgumentException::class)
    fun setActiveRejectsAnUnknownProfileId() {
        FakeScaleProfileStore(listOf(profile())).setActive("no-such-profile")
    }

    @Test
    fun setActiveMovesTheActiveFlagToExactlyOneProfile() {
        val store = FakeScaleProfileStore(
            listOf(profile(id = "first", scaleIndex = 1), profile(id = "second", scaleIndex = 2, active = false)),
        )

        store.setActive("second")

        assertEquals("second", store.activeProfile.value?.id)
        assertEquals(1, store.profiles.value.count { it.active })
    }

    // --- The address+index overload, which the capture path uses to pick a scale.

    @Test
    fun theIndexedCredentialLookupDistinguishesTwoProfilesOnTheSameDevice() {
        val store = FakeScaleProfileStore(
            listOf(
                profile(id = "slot-1", scaleIndex = 1, consentCode = 1111),
                profile(id = "slot-2", scaleIndex = 2, consentCode = 2222, active = false),
            ),
        )

        assertEquals(ScaleCredential(1, 1111), store.credentialFor(address, 1))
        assertEquals(ScaleCredential(2, 2222), store.credentialFor(address, 2))
        assertNull("an unregistered slot on a known device has no credential", store.credentialFor(address, 3))
    }
}
