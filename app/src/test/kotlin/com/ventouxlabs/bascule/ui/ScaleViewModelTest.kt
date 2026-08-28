package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.data.ScaleProfile
import com.ventouxlabs.bascule.data.fake.FakeScaleProfileStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * C4: `ui/ScaleScreen.kt`'s ViewModel. Every branch here is user-visible on a
 * screen whose only error surface is a single diagnostic line, so the cases
 * that matter are the ones where the toggle and the armed scan can disagree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScaleViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val address = "E7:DB:51:F1:36:91"

    private fun profile(
        id: String = "slot-2",
        scaleIndex: Int = 2,
        label: String = "Profile 2",
        active: Boolean = true,
    ) = ScaleProfile(
        id = id,
        deviceAddress = address,
        scaleIndex = scaleIndex,
        consentCode = 1234,
        label = label,
        registeredAtMillis = 1_000L,
        active = active,
    )

    private class Recorder(private val armResult: Boolean = true) : BridgeServiceController {
        var armCount = 0
            private set
        var disarmCount = 0
            private set
        val bridgeCalls = mutableListOf<String>()

        suspend fun arm(): Boolean {
            armCount++
            return armResult
        }

        fun disarm() {
            disarmCount++
        }

        override fun start() {
            bridgeCalls += "start"
        }

        override fun stop() {
            bridgeCalls += "stop"
        }
    }

    private fun viewModel(
        config: FakeConfigStore = FakeConfigStore(),
        profiles: FakeScaleProfileStore = FakeScaleProfileStore(),
        dao: FakeReadingDao = FakeReadingDao(),
        recorder: Recorder = Recorder(),
    ) = ScaleViewModel(
        config = config,
        profiles = profiles,
        dao = dao,
        onArm = recorder::arm,
        onDisarm = recorder::disarm,
        bridgeService = recorder,
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

    /**
     * `uiState` shares with `WhileSubscribed`, so with no collector it never
     * leaves its initial value and every assertion against it would read the
     * defaults instead of the ViewModel's actual state.
     */
    private fun TestScope.collecting(vm: ScaleViewModel): ScaleViewModel {
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    // --- L3: the seed emission is not an answer about the registry.

    /**
     * `stateIn`'s seed is a `ScaleUiState()` with an empty profile list, which
     * is indistinguishable from a genuinely empty registry — so `ScaleScreen`
     * flashed "No locally known profiles" on every open, including for users
     * who have one registered.
     */
    @Test
    fun theSeedStateIsLoadingAndTheFirstRealEmissionClearsIt() = runTest {
        val profiles = FakeScaleProfileStore(listOf(profile()))
        val vm = collecting(viewModel(profiles = profiles))

        assertTrue("the seed value must not claim the registry is empty", vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.profiles.isEmpty())

        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(listOf(profile()), vm.uiState.value.profiles)
    }

    /**
     * A genuinely empty registry must still resolve to "not loading" — the
     * empty state has to be reachable, or the spinner never goes away for a
     * user who has registered nothing.
     */
    @Test
    fun anEmptyRegistryStillClearsTheLoadingFlag() = runTest {
        val vm = collecting(viewModel(profiles = FakeScaleProfileStore()))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.profiles.isEmpty())
    }

    // --- A quarantined registry blob must not be silently invisible to the user.

    /**
     * Devil's-advocate review, error handling round: `EncryptedScaleProfileStore`
     * quarantines a corrupt blob instead of destroying it, but nothing surfaced
     * that to the user — the data was safe while the failure stayed silent.
     */
    @Test
    fun aReadFailureSurfacesOnceAsTheInitialDiagnostic() = runTest {
        val profiles = FakeScaleProfileStore(readFailure = IllegalArgumentException("boom"))
        val vm = collecting(viewModel(profiles = profiles))
        advanceUntilIdle()

        assertEquals(ScaleViewModel.REGISTRY_UNREADABLE_MESSAGE, vm.uiState.value.diagnostic)
    }

    @Test
    fun noReadFailureLeavesTheInitialDiagnosticClear() = runTest {
        val vm = collecting(viewModel(profiles = FakeScaleProfileStore()))
        advanceUntilIdle()

        assertNull(vm.uiState.value.diagnostic)
    }

    /**
     * L4: this ViewModel is activity-scoped (`BasculeApp.kt` hoists
     * `LocalViewModelStoreOwner` above the `NavHost` so Settings and Scale
     * share one instance), so leaving the Scale tab does not recreate it and
     * there is no navigation event to clear the diagnostic on. What has to
     * hold instead is that the seeded registry-unreadable notice is genuinely
     * one-shot: the first toggle interaction replaces it, so it cannot
     * outlive the condition it described for the life of the process.
     */
    @Test
    fun theSeededReadFailureNoticeDoesNotSurviveTheFirstToggleInteraction() = runTest {
        val profiles = FakeScaleProfileStore(listOf(profile()), readFailure = IllegalArgumentException("boom"))
        val vm = collecting(viewModel(profiles = profiles))
        advanceUntilIdle()
        assertEquals(ScaleViewModel.REGISTRY_UNREADABLE_MESSAGE, vm.uiState.value.diagnostic)

        vm.setAutomaticCapture(true)
        advanceUntilIdle()

        assertNull("a one-time notice must not become a permanent banner", vm.uiState.value.diagnostic)
    }

    // --- Enabling automatic capture without an active profile arms nothing.

    @Test
    fun enablingAutomaticCaptureWithNoActiveProfileNeitherPersistsNorArms() = runTest {
        val config = FakeConfigStore()
        val recorder = Recorder()
        val vm = collecting(viewModel(config = config, recorder = recorder))
        advanceUntilIdle()

        vm.setAutomaticCapture(true)
        advanceUntilIdle()

        assertFalse(
            "the flag must not persist, or the next launch arms a scan with no profile to filter on",
            config.automaticCaptureEnabled.value,
        )
        assertEquals("nothing to arm without an active profile", 0, recorder.armCount)
        assertFalse(
            "the toggle must read off — a UI claiming capture is on while nothing is armed is the worst case",
            vm.uiState.value.automaticCaptureEnabled,
        )
        assertNotNull("the user needs to be told why the toggle refused", vm.uiState.value.diagnostic)
    }

    /**
     * A profile exists but is inactive: `activeProfile` is what `arm()` filters
     * on, so an inactive-only registry is the same "nothing to arm" state as an
     * empty one, and must be reported the same way.
     */
    @Test
    fun enablingAutomaticCaptureWithOnlyInactiveProfilesIsTreatedAsNoActiveProfile() = runTest {
        val config = FakeConfigStore()
        val profiles = FakeScaleProfileStore(listOf(profile(active = false)))
        val recorder = Recorder()
        val vm = collecting(viewModel(config = config, profiles = profiles, recorder = recorder))
        advanceUntilIdle()

        vm.setAutomaticCapture(true)
        advanceUntilIdle()

        assertFalse(config.automaticCaptureEnabled.value)
        assertEquals(0, recorder.armCount)
        assertNotNull(vm.uiState.value.diagnostic)
    }

    @Test
    fun enablingAutomaticCaptureWithAnActiveProfilePersistsArmsAndClearsTheDiagnostic() = runTest {
        val config = FakeConfigStore()
        val profiles = FakeScaleProfileStore(listOf(profile()))
        val recorder = Recorder()
        val vm = collecting(viewModel(config = config, profiles = profiles, recorder = recorder))
        advanceUntilIdle()

        vm.setAutomaticCapture(true)
        advanceUntilIdle()

        assertTrue(config.automaticCaptureEnabled.value)
        assertEquals(1, recorder.armCount)
        assertTrue(vm.uiState.value.automaticCaptureEnabled)
        assertNull(vm.uiState.value.diagnostic)
    }

    /**
     * Current behaviour, asserted as-is: the flag is persisted *before* `onArm()`
     * is consulted, so a refused arm leaves the stored flag and the toggle both
     * reading "on" while no scan is registered. Only the diagnostic line records
     * the divergence — see the report accompanying this wave.
     */
    @Test
    fun aRefusedArmStillLeavesTheFlagPersistedAndOnlySaysSoInTheDiagnostic() = runTest {
        val config = FakeConfigStore()
        val profiles = FakeScaleProfileStore(listOf(profile()))
        val recorder = Recorder(armResult = false)
        val vm = collecting(viewModel(config = config, profiles = profiles, recorder = recorder))
        advanceUntilIdle()

        vm.setAutomaticCapture(true)
        advanceUntilIdle()

        assertTrue("persisted before the arm result is known", config.automaticCaptureEnabled.value)
        assertTrue("so the toggle reads on while nothing is armed", vm.uiState.value.automaticCaptureEnabled)
        assertEquals(
            "Background scan could not be armed. Check Bluetooth and permissions.",
            vm.uiState.value.diagnostic,
        )
    }

    @Test
    fun disablingAutomaticCaptureDisarmsAndClearsAnyStandingDiagnostic() = runTest {
        val config = FakeConfigStore()
        val profiles = FakeScaleProfileStore(listOf(profile()))
        val recorder = Recorder(armResult = false)
        val vm = collecting(viewModel(config = config, profiles = profiles, recorder = recorder))
        advanceUntilIdle()
        vm.setAutomaticCapture(true)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.diagnostic)

        vm.setAutomaticCapture(false)
        advanceUntilIdle()

        assertFalse(config.automaticCaptureEnabled.value)
        assertEquals(1, recorder.disarmCount)
        assertNull("a stale arm failure must not outlive the toggle it described", vm.uiState.value.diagnostic)
    }

    // --- Switching the active profile has to move the scan filter with it.

    @Test
    fun setActiveReArmsWhenAutomaticCaptureIsOn() = runTest {
        val config = FakeConfigStore()
        val profiles = FakeScaleProfileStore(listOf(profile(), profile(id = "slot-3", scaleIndex = 3, active = false)))
        val recorder = Recorder()
        val vm = collecting(viewModel(config = config, profiles = profiles, recorder = recorder))
        advanceUntilIdle()
        vm.setAutomaticCapture(true)
        advanceUntilIdle()
        val armsAfterEnabling = recorder.armCount

        vm.setActive("slot-3")
        advanceUntilIdle()

        assertEquals("slot-3", profiles.activeProfile.value?.id)
        assertEquals(
            "the scan is filtered on the active profile's address, so switching must re-arm",
            armsAfterEnabling + 1,
            recorder.armCount,
        )
    }

    @Test
    fun setActiveDoesNotArmWhileAutomaticCaptureIsOff() = runTest {
        val profiles = FakeScaleProfileStore(listOf(profile(), profile(id = "slot-3", scaleIndex = 3, active = false)))
        val recorder = Recorder()
        val vm = collecting(viewModel(profiles = profiles, recorder = recorder))
        advanceUntilIdle()

        vm.setActive("slot-3")
        advanceUntilIdle()

        assertEquals("slot-3", profiles.activeProfile.value?.id)
        assertEquals("arming a scan the user has switched off would capture behind their back", 0, recorder.armCount)
    }

    /**
     * Current behaviour, asserted as-is: `setActive` discards `onArm()`'s result,
     * so a re-arm that fails leaves the scan filtered on the *previous* profile's
     * address with nothing said about it — unlike `setAutomaticCapture`, which
     * surfaces the same failure. See the report accompanying this wave.
     */
    @Test
    fun setActiveReportsNothingWhenTheReArmFails() = runTest {
        val config = FakeConfigStore()
        val profiles = FakeScaleProfileStore(listOf(profile(), profile(id = "slot-3", scaleIndex = 3, active = false)))
        val recorder = Recorder(armResult = false)
        val vm = collecting(viewModel(config = config, profiles = profiles, recorder = recorder))
        advanceUntilIdle()
        config.saveAutomaticCaptureEnabled(true)
        advanceUntilIdle()

        vm.setActive("slot-3")
        advanceUntilIdle()

        assertEquals(1, recorder.armCount)
        assertNull("the failed re-arm leaves no trace on the only error surface", vm.uiState.value.diagnostic)
    }

    // --- rename(): the editor closes regardless, so the guards decide what the user keeps.

    @Test
    fun renameTrimsSurroundingWhitespace() = runTest {
        val profiles = FakeScaleProfileStore(listOf(profile()))
        val vm = viewModel(profiles = profiles)

        vm.rename(profile(), "  Kitchen scale \n")
        advanceUntilIdle()

        assertEquals("Kitchen scale", profiles.profiles.value.single().label)
    }

    @Test
    fun renameTruncatesToFortyCharacters() = runTest {
        val profiles = FakeScaleProfileStore(listOf(profile()))
        val vm = viewModel(profiles = profiles)

        vm.rename(profile(), "x".repeat(100))
        advanceUntilIdle()

        assertEquals(40, profiles.profiles.value.single().label.length)
    }

    /**
     * `ScaleScreen` closes the label editor whether or not this stores anything,
     * so a whitespace-only label reads on screen as a rename that succeeded and
     * then reverted.
     */
    @Test
    fun renameIgnoresALabelThatIsEmptyAfterTrimming() = runTest {
        val profiles = FakeScaleProfileStore(listOf(profile(label = "Original")))
        val vm = viewModel(profiles = profiles)

        vm.rename(profile(label = "Original"), "   ")
        advanceUntilIdle()

        assertEquals("Original", profiles.profiles.value.single().label)
    }

    @Test
    fun renamePreservesEveryOtherFieldOfTheProfile() = runTest {
        val profiles = FakeScaleProfileStore(listOf(profile()))
        val vm = viewModel(profiles = profiles)

        vm.rename(profile(), "Renamed")
        advanceUntilIdle()

        assertEquals(profile().copy(label = "Renamed"), profiles.profiles.value.single())
    }

    // --- Always-on bridging.

    @Test
    fun enablingAlwaysOnBridgingPersistsTheFlagAndStartsTheService() = runTest {
        val config = FakeConfigStore()
        val recorder = Recorder()
        val vm = collecting(viewModel(config = config, recorder = recorder))
        advanceUntilIdle()

        vm.setAlwaysOnBridging(true)
        advanceUntilIdle()

        assertTrue(config.alwaysOnBridging.value)
        assertEquals(listOf("start"), recorder.bridgeCalls)
        assertTrue(vm.uiState.value.alwaysOnBridging)
    }

    @Test
    fun disablingAlwaysOnBridgingStopsTheService() = runTest {
        val config = FakeConfigStore(initialAlwaysOnBridging = true)
        val recorder = Recorder()
        val vm = viewModel(config = config, recorder = recorder)
        advanceUntilIdle()

        vm.setAlwaysOnBridging(false)
        advanceUntilIdle()

        assertFalse(config.alwaysOnBridging.value)
        assertEquals(listOf("stop"), recorder.bridgeCalls)
    }
}
