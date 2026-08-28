package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.ble.RegistrationPhase
import com.ventouxlabs.bascule.ble.ScaleRegistrar
import com.ventouxlabs.bascule.ble.ScaleRegistrationResult
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.ui.fake.FakeAuthTokenStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.FakeSessionCookieStore
import com.ventouxlabs.bascule.ui.fake.FakeVitalForgeApi
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * C9/C10: [ConfigViewModel]'s two ways of establishing a scale mapping —
 * `startScaleRegistration`, which burns one of the BF720's eight slots, and
 * `linkExistingScale`, the hand-entry path that restores a mapping without
 * burning one. Split from [ConfigViewModelTest] for the same reason
 * [ConfigViewModelProfileRegistryTest] was: the combined class is already at
 * detekt's size threshold.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelScaleRegistrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val address = "E7:DB:51:F1:36:91"

    /**
     * Suspends between the two phase callbacks so each intermediate UI state can
     * be observed. `uiState` is a conflating `StateFlow`, so a registrar that ran
     * to completion in one dispatch would let `Scanning` be overwritten before
     * anything could read it.
     */
    private class GatedRegistrar(
        private val result: ScaleRegistrationResult = ScaleRegistrationResult.Success(
            "E7:DB:51:F1:36:91",
            2,
        ),
        val scanning: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        val connecting: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    ) : ScaleRegistrar {
        var callCount = 0
            private set
        var lastForceNew: Boolean? = null
            private set

        override suspend fun register(
            forceNew: Boolean,
            onPhase: (RegistrationPhase) -> Unit,
        ): ScaleRegistrationResult {
            callCount++
            lastForceNew = forceNew
            onPhase(RegistrationPhase.SCANNING)
            scanning.await()
            onPhase(RegistrationPhase.CONNECTING)
            connecting.await()
            return result
        }
    }

    private fun viewModel(
        configStore: FakeConfigStore = FakeConfigStore(),
        consentStore: InMemoryConsentStore = InMemoryConsentStore(),
        scaleRegistrar: ScaleRegistrar? = null,
    ) = ConfigViewModel(
        configStore,
        FakeAuthTokenStore(),
        consentStore,
        FakeSessionCookieStore(),
        FakeDeliveryTrigger(),
        FakeReadingDao(),
        ioDispatcher = mainDispatcherRule.dispatcher,
        scaleRegistrar = scaleRegistrar,
        apiFactory = { FakeVitalForgeApi() },
    )

    /**
     * `uiState` shares with `WhileSubscribed`, so with no collector it never
     * leaves its initial value and every assertion against it would read the
     * defaults instead of the ViewModel's actual state.
     */
    private fun TestScope.collecting(vm: ConfigViewModel): ConfigViewModel {
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    // --- C10: startScaleRegistration.

    @Test
    fun registrationFailureReachesTheUserVerbatimRatherThanAsGenericCopy() = runTest {
        val registrar = GatedRegistrar(
            result = ScaleRegistrationResult.Failure("Handshake rejected: consent code refused"),
        )
        val vm = collecting(viewModel(scaleRegistrar = registrar))
        advanceUntilIdle()

        vm.startScaleRegistration()
        advanceUntilIdle()

        assertEquals(
            "WP-07 already recorded a flattened message telling the user something false",
            ScaleRegistrationUiState.Failure("Handshake rejected: consent code refused"),
            vm.uiState.value.scaleRegistration,
        )
    }

    @Test
    fun registrationWithoutARegistrarReportsUnavailableRatherThanDoingNothing() = runTest {
        val vm = collecting(viewModel())
        advanceUntilIdle()

        vm.startScaleRegistration()
        advanceUntilIdle()

        assertEquals(
            ScaleRegistrationUiState.Failure("Scale registration is unavailable"),
            vm.uiState.value.scaleRegistration,
        )
    }

    @Test
    fun registrationPhasesAreObservableAsScanningThenConnecting() = runTest {
        val registrar = GatedRegistrar(
            scanning = CompletableDeferred(),
            connecting = CompletableDeferred(),
        )
        val vm = collecting(viewModel(scaleRegistrar = registrar))
        advanceUntilIdle()

        vm.startScaleRegistration()
        advanceUntilIdle()
        assertEquals(ScaleRegistrationUiState.Scanning, vm.uiState.value.scaleRegistration)

        registrar.scanning.complete(Unit)
        advanceUntilIdle()
        assertEquals(ScaleRegistrationUiState.Connecting, vm.uiState.value.scaleRegistration)

        registrar.connecting.complete(Unit)
        advanceUntilIdle()
        assertEquals(ScaleRegistrationUiState.Success(address, 2), vm.uiState.value.scaleRegistration)
    }

    @Test
    fun aTapWhileAlreadyScanningIsIgnored() = runTest {
        val registrar = GatedRegistrar(scanning = CompletableDeferred())
        val vm = collecting(viewModel(scaleRegistrar = registrar))
        advanceUntilIdle()

        vm.startScaleRegistration()
        advanceUntilIdle()
        assertEquals(ScaleRegistrationUiState.Scanning, vm.uiState.value.scaleRegistration)

        vm.startScaleRegistration()
        advanceUntilIdle()

        assertEquals(
            "once the phase callback has landed, the guard holds",
            1,
            registrar.callCount,
        )
    }

    /**
     * Pins *what the busy guard rests on*, not a production defect.
     *
     * Unlike `testConnection` (which writes `Testing`) and `login` (which writes
     * `isLoggingIn`), `startScaleRegistration` writes nothing synchronously
     * before `viewModelScope.launch`: its guard reads a state that only the
     * registrar's own phase callback sets, from inside the coroutine. On a
     * device that still holds, because `viewModelScope` uses
     * `Dispatchers.Main.immediate` and [com.ventouxlabs.bascule.ble.AndroidScaleRegistrar]
     * emits `SCANNING` before it first suspends — so the guard is armed by the
     * time the tap handler returns.
     *
     * Both of those are invisible at the guard site. This test removes them
     * (a queueing `StandardTestDispatcher`, a registrar that suspends before
     * signalling) and shows the guard stops holding — which is why the other
     * two operations' write-before-`launch` shape is the more robust one, on the
     * one operation of the three that consumes a scale slot. See this wave's
     * report; a defensive fix would turn this test red, which is the intent.
     */
    @Test
    fun theBusyGuardHoldsOnlyBecauseTheRegistrarSignalsBeforeItFirstSuspends() = runTest {
        val registrar = GatedRegistrar(scanning = CompletableDeferred())
        val vm = collecting(viewModel(scaleRegistrar = registrar))
        advanceUntilIdle()

        vm.startScaleRegistration()
        vm.startScaleRegistration()
        advanceUntilIdle()

        assertEquals(
            "with the phase signal deferred, nothing synchronous stops the second tap",
            2,
            registrar.callCount,
        )
    }

    @Test
    fun reRegisterAsksTheRegistrarForANewSlotRatherThanReusingTheStoredOne() = runTest {
        val registrar = GatedRegistrar()
        val vm = collecting(viewModel(scaleRegistrar = registrar))
        advanceUntilIdle()

        vm.reRegister()
        advanceUntilIdle()

        assertEquals(true, registrar.lastForceNew)
    }

    // --- C9: linkExistingScale's validation branches.

    private fun assertLinkRejected(
        scaleIndex: String,
        consentCode: String,
        expectedMessage: String,
        deviceAddress: String = "e7:db:51:f1:36:91",
    ) = runTest {
        val configStore = FakeConfigStore()
        val consentStore = InMemoryConsentStore()
        val vm = collecting(viewModel(configStore = configStore, consentStore = consentStore))
        advanceUntilIdle()

        vm.linkExistingScale(deviceAddress, scaleIndex, consentCode)
        advanceUntilIdle()

        assertEquals(
            ScaleRegistrationUiState.Failure(expectedMessage),
            vm.uiState.value.scaleRegistration,
        )
        assertNull("a rejected mapping must not be paired", configStore.pairedDeviceAddress.value)
        assertNull(
            "a rejected mapping must not reach the consent store either",
            consentStore.credentialFor(address),
        )
    }

    @Test
    fun linkingRejectsAMalformedBluetoothAddress() =
        assertLinkRejected(
            deviceAddress = "not-an-address",
            scaleIndex = "2",
            consentCode = "1234",
            expectedMessage = "Enter a valid Bluetooth address",
        )

    @Test
    fun linkingRejectsAScaleIndexAboveTheEightBitRange() =
        assertLinkRejected(
            scaleIndex = "256",
            consentCode = "1234",
            expectedMessage = "User slot must be between 0 and 255",
        )

    @Test
    fun linkingRejectsAConsentCodeAboveTheSixteenBitRange() =
        assertLinkRejected(
            scaleIndex = "2",
            consentCode = "65536",
            expectedMessage = "Consent code must be between 0 and 65535",
        )

    /**
     * `toIntOrNull()` returning null falls through to the range check — `null !in
     * anIntRange` is true — so a non-numeric slot and an out-of-range one produce
     * the same message. Pinned as current behaviour; noted in this wave's report.
     */
    @Test
    fun linkingReportsANonNumericScaleIndexWithTheRangeMessage() =
        assertLinkRejected(
            scaleIndex = "two",
            consentCode = "1234",
            expectedMessage = "User slot must be between 0 and 255",
        )

    @Test
    fun linkingReportsANonNumericConsentCodeWithTheRangeMessage() =
        assertLinkRejected(
            scaleIndex = "2",
            consentCode = "twelve thirty four",
            expectedMessage = "Consent code must be between 0 and 65535",
        )

    @Test
    fun linkingAcceptsBothRangeBoundaries() = runTest {
        val consentStore = InMemoryConsentStore()
        val vm = collecting(viewModel(consentStore = consentStore))
        advanceUntilIdle()

        vm.linkExistingScale(address, "255", "65535")
        advanceUntilIdle()

        assertEquals(ScaleRegistrationUiState.Success(address, 255), vm.uiState.value.scaleRegistration)
    }
}
