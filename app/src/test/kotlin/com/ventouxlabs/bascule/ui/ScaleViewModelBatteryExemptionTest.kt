package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.data.fake.FakeScaleProfileStore
import com.ventouxlabs.bascule.ui.fake.FakeCaptureAttemptLog
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Without the battery-optimisation exemption Android refuses the background
 * foreground-service start and every scale wake-up is skipped, so the screen
 * has to say so — and stop saying so once the user has fixed it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScaleViewModelBatteryExemptionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class NoopBridge : BridgeServiceController {
        override fun start() = Unit
        override fun startBounded(durationMillis: Long) = Unit
        override fun stop() = Unit
        override fun cancelBounded() = Unit
        override fun rearmScan() = Unit
    }

    private fun viewModel(isBatteryExempt: () -> Boolean) = ScaleViewModel(
        config = FakeConfigStore(),
        profiles = FakeScaleProfileStore(),
        dao = FakeReadingDao(),
        onArm = { true },
        onDisarm = {},
        bridgeService = NoopBridge(),
        captureAttempts = FakeCaptureAttemptLog(),
        isBatteryExempt = isBatteryExempt,
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

    private fun TestScope.collecting(vm: ScaleViewModel): ScaleViewModel {
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun aNonExemptAppIsToldToAskForTheExemption() = runTest {
        val vm = collecting(viewModel { false })
        advanceUntilIdle()

        assertTrue(vm.uiState.value.batteryExemptionNeeded)
    }

    @Test
    fun anExemptAppIsNotNagged() = runTest {
        var calls = 0
        val vm = collecting(viewModel { calls++; true })
        advanceUntilIdle()

        assertFalse(vm.uiState.value.batteryExemptionNeeded)
        assertEquals("asked once when state is first computed", 1, calls)

        vm.refreshBatteryExemption()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.batteryExemptionNeeded)
        assertEquals("asked again on refresh", 2, calls)
    }

    @Test
    fun returningFromTheSystemDialogWithTheExemptionGrantedClearsTheBanner() = runTest {
        var exempt = false
        val vm = collecting(viewModel { exempt })
        advanceUntilIdle()
        assertTrue(vm.uiState.value.batteryExemptionNeeded)

        exempt = true
        vm.refreshBatteryExemption()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.batteryExemptionNeeded)
    }

    @Test
    fun revokingTheExemptionInSettingsBringsTheBannerBack() = runTest {
        var exempt = true
        val vm = collecting(viewModel { exempt })
        advanceUntilIdle()
        assertFalse(vm.uiState.value.batteryExemptionNeeded)

        exempt = false
        vm.refreshBatteryExemption()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.batteryExemptionNeeded)
    }
}
