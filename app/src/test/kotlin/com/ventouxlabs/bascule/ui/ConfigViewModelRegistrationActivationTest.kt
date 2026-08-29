package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.ble.RegistrationPhase
import com.ventouxlabs.bascule.ble.ScaleRegistrar
import com.ventouxlabs.bascule.ble.ScaleRegistrationResult
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.data.ScaleProfile
import com.ventouxlabs.bascule.data.ScaleProfileStore
import com.ventouxlabs.bascule.data.fake.FakeScaleProfileStore
import com.ventouxlabs.bascule.ui.fake.FakeAuthTokenStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.FakeSessionCookieStore
import com.ventouxlabs.bascule.ui.fake.FakeVitalForgeApi
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * M1/M2 on the *registration* path: `startScaleRegistration` must leave the
 * scale it just registered active and the scan re-armed onto it, the same way
 * `linkExistingScale` does. The registry stores a profile inactive whenever
 * another one already holds the active flag, and only the active profile is
 * scanned and captured for — so without that activation the screen reports
 * success and capture is silently dead.
 *
 * Its own class rather than an addition to [ConfigViewModelScaleRegistrationTest]
 * (which wires no profile store) or [ConfigViewModelProfileRegistryTest] (which
 * wires no registrar): these are the only cases that need both, plus a scanner
 * standing in for `ScaleScanner.arm()`'s gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelRegistrationActivationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val newAddress = "E7:DB:51:F1:36:91"
    private val oldAddress = "AA:BB:CC:DD:EE:FF"

    /**
     * Stands in for [com.ventouxlabs.bascule.ble.AndroidScaleRegistrar]: the
     * credential reaches the registry through `ConsentStore.save`, which is what
     * leaves the new profile inactive when another one is already active. A
     * registrar that only returned a result would not reproduce the defect.
     */
    private class SavingRegistrar(
        private val store: ScaleProfileStore,
        private val configStore: FakeConfigStore,
        private val address: String,
        private val scaleIndex: Int,
        private val consentCode: Int,
    ) : ScaleRegistrar {
        override suspend fun register(
            forceNew: Boolean,
            onPhase: (RegistrationPhase) -> Unit,
        ): ScaleRegistrationResult {
            onPhase(RegistrationPhase.SCANNING)
            onPhase(RegistrationPhase.CONNECTING)
            store.save(address, ScaleCredential(scaleIndex, consentCode))
            configStore.savePairedDeviceAddress(address)
            return ScaleRegistrationResult.Success(address, scaleIndex)
        }
    }

    /**
     * Mirrors [com.ventouxlabs.bascule.ble.ScaleScanner]'s two gates — automatic
     * capture on, and an active profile to filter on — so "the scanner is armed"
     * is asserted against the same conditions production evaluates rather than a
     * bare call count.
     */
    private class RecordingScanner(
        private val configStore: FakeConfigStore,
        private val store: ScaleProfileStore,
    ) {
        var armedAddress: String? = null
            private set

        suspend fun rearm() {
            armedAddress = null
            if (!configStore.automaticCaptureEnabled.first()) return
            armedAddress = store.activeProfile.value?.deviceAddress
        }
    }

    private fun profile(id: String, address: String, scaleIndex: Int, active: Boolean = true) = ScaleProfile(
        id = id,
        deviceAddress = address,
        scaleIndex = scaleIndex,
        consentCode = 4321,
        label = "Profile $scaleIndex",
        registeredAtMillis = 1_000L,
        active = active,
    )

    private fun viewModel(
        registry: FakeScaleProfileStore,
        configStore: FakeConfigStore,
        registrar: ScaleRegistrar,
        rearmScanner: suspend () -> Unit,
    ) = ConfigViewModel(
        configStore,
        FakeAuthTokenStore(),
        registry,
        FakeSessionCookieStore(),
        FakeDeliveryTrigger(),
        FakeReadingDao(),
        ioDispatcher = mainDispatcherRule.dispatcher,
        scaleRegistrar = registrar,
        scaleProfileStore = registry,
        rearmScanner = rearmScanner,
        apiFactory = { FakeVitalForgeApi() },
    )

    @Test
    fun registeringASecondScaleMakesItActiveAndReArmsTheScanOntoIt() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("first", oldAddress, scaleIndex = 1)))
        val configStore = FakeConfigStore(initialPairedDeviceAddress = oldAddress)
        configStore.saveAutomaticCaptureEnabled(true)
        val scanner = RecordingScanner(configStore, registry)
        val vm = viewModel(
            registry,
            configStore,
            SavingRegistrar(registry, configStore, newAddress, scaleIndex = 2, consentCode = 1234),
            scanner::rearm,
        )
        advanceUntilIdle()

        vm.startScaleRegistration()
        advanceUntilIdle()

        assertEquals(newAddress, registry.activeProfile.value?.deviceAddress)
        assertEquals(2, registry.activeProfile.value?.scaleIndex)
        assertEquals(
            "the scan must filter on the scale just registered, not the replaced one",
            newAddress,
            scanner.armedAddress,
        )
    }

    /**
     * `ScaleScanner.arm()` returns false when no active profile exists, so a
     * user who turns automatic capture on before registering gets nothing from
     * the toggle — registration is the only thing that can arm it afterwards.
     */
    @Test
    fun registeringTheFirstScaleWithAutomaticCaptureAlreadyOnActuallyStartsScanning() = runTest {
        val registry = FakeScaleProfileStore()
        val configStore = FakeConfigStore()
        configStore.saveAutomaticCaptureEnabled(true)
        val scanner = RecordingScanner(configStore, registry)
        val vm = viewModel(
            registry,
            configStore,
            SavingRegistrar(registry, configStore, newAddress, scaleIndex = 2, consentCode = 1234),
            scanner::rearm,
        )
        advanceUntilIdle()

        vm.startScaleRegistration()
        advanceUntilIdle()

        assertEquals(newAddress, scanner.armedAddress)
    }

    /**
     * Re-registering the same scale to rotate consent takes a fresh slot, so the
     * registry holds two profiles for one address. `credentialFor` prefers the
     * active one — leaving the new slot inactive would make every session keep
     * consenting with the slot this re-registration was meant to replace.
     */
    @Test
    fun reRegisteringTheSameScaleMakesTheNewSlotTheOneSessionsConsentWith() = runTest {
        val registry = FakeScaleProfileStore(listOf(profile("old-slot", newAddress, scaleIndex = 2)))
        val configStore = FakeConfigStore(initialPairedDeviceAddress = newAddress)
        configStore.saveAutomaticCaptureEnabled(true)
        val scanner = RecordingScanner(configStore, registry)
        val vm = viewModel(
            registry,
            configStore,
            SavingRegistrar(registry, configStore, newAddress, scaleIndex = 3, consentCode = 5555),
            scanner::rearm,
        )
        advanceUntilIdle()

        vm.reRegister()
        advanceUntilIdle()

        assertEquals(ScaleCredential(3, 5555), registry.credentialFor(newAddress))
        assertEquals(2, registry.profiles.value.size)
        assertEquals(newAddress, scanner.armedAddress)
    }
}
