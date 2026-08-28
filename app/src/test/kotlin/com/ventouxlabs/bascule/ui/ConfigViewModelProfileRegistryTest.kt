package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.data.BackupCredentialType
import com.ventouxlabs.bascule.data.PortableSettings
import com.ventouxlabs.bascule.data.ScaleProfile
import com.ventouxlabs.bascule.data.ScaleProfileStore
import com.ventouxlabs.bascule.data.SettingsBackupCodec
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.data.fake.FakeScaleProfileStore
import com.ventouxlabs.bascule.network.ContractVersion
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.ble.ScaleRegistrar
import com.ventouxlabs.bascule.ui.fake.FakeAuthTokenStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.FakeSessionCookieStore
import com.ventouxlabs.bascule.ui.fake.FakeVitalForgeApi
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [ConfigViewModel]'s scale-profile registry paths: export, import, and manual
 * linking. Split from [ConfigViewModelTest] because these are the only cases
 * that need a real [ScaleProfileStore] wired in, and because the combined class
 * exceeded detekt's LargeClass threshold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelProfileRegistryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        configStore: FakeConfigStore = FakeConfigStore(),
        consentStore: ConsentStore = InMemoryConsentStore(),
        sessionCookieStore: FakeSessionCookieStore = FakeSessionCookieStore(),
        scaleRegistrar: ScaleRegistrar? = null,
        scaleProfileStore: ScaleProfileStore? = null,
        rearmScanner: (suspend () -> Unit)? = null,
    ) = ConfigViewModel(
        configStore,
        FakeAuthTokenStore(),
        consentStore,
        sessionCookieStore,
        FakeDeliveryTrigger(),
        FakeReadingDao(),
        ioDispatcher = mainDispatcherRule.dispatcher,
        scaleRegistrar = scaleRegistrar,
        scaleProfileStore = scaleProfileStore,
        rearmScanner = rearmScanner,
        apiFactory = { FakeVitalForgeApi() },
    )

    /**
     * Production hands the same [com.ventouxlabs.bascule.data.EncryptedScaleProfileStore]
     * in as both collaborators ([com.ventouxlabs.bascule.BasculeApplication] `consentStore`
     * delegates to `scaleProfileStore`), so a test that supplies only one of them
     * exercises a wiring that never ships.
     */
    private fun viewModelWithRegistry(
        registry: FakeScaleProfileStore,
        configStore: FakeConfigStore = FakeConfigStore(),
        sessionCookieStore: FakeSessionCookieStore = FakeSessionCookieStore(),
        rearmScanner: (suspend () -> Unit)? = null,
    ) = viewModel(
        configStore = configStore,
        consentStore = registry,
        sessionCookieStore = sessionCookieStore,
        scaleProfileStore = registry,
        rearmScanner = rearmScanner,
    )

    private fun profile(
        id: String,
        address: String = "E7:DB:51:F1:36:91",
        scaleIndex: Int = 2,
        consentCode: Int = 1234,
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

    // --- C1: the profile-registry export/import path, with the store production actually wires in.

    @Test
    fun settingsExportRoundTripsTheProfileRegistryThroughEncryption() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("bf720-slot-2")))
        val configStore = FakeConfigStore(
            initialBaseUrl = "https://weight.grepon.cc",
            initialPairedDeviceAddress = "E7:DB:51:F1:36:91",
        )
        val vm = viewModelWithRegistry(registry, configStore)
        advanceUntilIdle()

        val bytes = vm.exportSettings("correct horse battery staple").getOrThrow()
        val restored = SettingsBackupCodec.decrypt(bytes, "correct horse battery staple")

        assertEquals(listOf(profile("bf720-slot-2")), restored.profiles)
        assertTrue("E7:DB:51:F1:36:91" !in bytes.decodeToString())
    }

    @Test
    fun importingABackupWithProfilesReplacesTheRegistryRatherThanTakingTheLegacyBranch() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("stale", scaleIndex = 5, consentCode = 999)))
        val configStore = FakeConfigStore(initialPairedDeviceAddress = "E7:DB:51:F1:36:91")
        val vm = viewModelWithRegistry(registry, configStore)
        val bytes = SettingsBackupCodec.encrypt(
            backupSettings(profiles = listOf(profile("imported"))),
            "correct horse battery staple",
        )

        vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals(listOf(profile("imported")), registry.profiles.value)
    }

    /**
     * TS-H5 / M12 end to end: a backup that carries no profile registry entries
     * is not evidence that this device has none. Deleting on that basis destroys
     * consent codes that can only be recovered by physically re-registering with
     * the scale, burning one of its eight slots.
     */
    @Test
    fun importingAZeroProfileBackupDoesNotWipeAnExistingRegistration() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("existing")))
        val configStore = FakeConfigStore(initialPairedDeviceAddress = "E7:DB:51:F1:36:91")
        val vm = viewModelWithRegistry(registry, configStore)
        val bytes = SettingsBackupCodec.encrypt(backupSettings(), "correct horse battery staple")

        vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals(listOf(profile("existing")), registry.profiles.value)
        assertEquals(ScaleCredential(2, 1234), registry.credentialFor("E7:DB:51:F1:36:91"))
    }

    @Test
    fun importingABackupWhoseProfilesAreAllInactiveIsRejectedBeforeAnythingIsWritten() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("existing")))
        val configStore = FakeConfigStore(initialBaseUrl = "https://original.example.com")
        val vm = viewModelWithRegistry(registry, configStore)
        val bytes = SettingsBackupCodec.encrypt(
            backupSettings(profiles = listOf(profile("imported", active = false))),
            "correct horse battery staple",
        )

        val result = vm.importSettings(bytes, "correct horse battery staple")
        advanceUntilIdle()

        assertTrue("a registry that can never arm must not import silently", result.isFailure)
        assertEquals(
            "the check must precede the first write, so no setting is left half-applied",
            "https://original.example.com",
            configStore.baseUrl.value,
        )
        assertEquals(listOf(profile("existing")), registry.profiles.value)
    }

    // --- M12: the scan registration reflects what the screen just changed.

    @Test
    fun importingSettingsReArmsTheScanner() = runTest {
        var rearmCount = 0
        val registry = FakeScaleProfileStore()
        val vm = viewModelWithRegistry(registry, rearmScanner = { rearmCount += 1 })
        val bytes = SettingsBackupCodec.encrypt(
            backupSettings(profiles = listOf(profile("imported"))),
            "correct horse battery staple",
        )

        vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals(1, rearmCount)
    }

    // --- M1: a linked scale that is not the active profile is never captured from.

    @Test
    fun linkingASecondScaleMakesItTheActiveProfileRatherThanReportingAnInertSuccess() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("first", address = "AA:BB:CC:DD:EE:FF", scaleIndex = 1)))
        var rearmCount = 0
        val vm = viewModelWithRegistry(registry, rearmScanner = { rearmCount += 1 })
        advanceUntilIdle()

        vm.linkExistingScale("e7:db:51:f1:36:91", "2", "1234")
        advanceUntilIdle()

        val active = registry.activeProfile.value
        assertEquals("E7:DB:51:F1:36:91", active?.deviceAddress)
        assertEquals(2, active?.scaleIndex)
        assertEquals("linking must re-arm the scan onto the newly active address", 1, rearmCount)
    }

    private fun backupSettings(profiles: List<ScaleProfile> = emptyList()) = PortableSettings(
        baseUrl = "https://mine.example.com",
        displayUnit = WeightUnit.KILOGRAMS,
        contractVersion = ContractVersion.V1_WEIGHT_ONLY,
        alwaysOnBridging = false,
        credentialType = BackupCredentialType.NONE,
        credentialValue = null,
        pairedDeviceAddress = "E7:DB:51:F1:36:91",
        scaleCredential = null,
        profiles = profiles,
    )
}
