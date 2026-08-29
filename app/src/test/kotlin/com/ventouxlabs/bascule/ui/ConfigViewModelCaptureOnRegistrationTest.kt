package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.ui.fake.FakeAuthTokenStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.FakeSessionCookieStore
import com.ventouxlabs.bascule.ui.fake.FakeVitalForgeApi
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Registration is the consent: nobody completes a scale handshake without
 * meaning for the app to read it. Both registration routes must agree —
 * enabling capture on only one would mean registering via the scale works and
 * linking by hand silently does not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelCaptureOnRegistrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun TestScope.viewModel(configStore: FakeConfigStore): ConfigViewModel {
        val vm = ConfigViewModel(
            configStore,
            FakeAuthTokenStore(),
            InMemoryConsentStore(),
            FakeSessionCookieStore(),
            FakeDeliveryTrigger(),
            FakeReadingDao(),
            ioDispatcher = mainDispatcherRule.dispatcher,
            apiFactory = { FakeVitalForgeApi() },
        )
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun linkingAnExistingScaleEnablesAutomaticCapture() = runTest {
        val configStore = FakeConfigStore()
        assertFalse(
            "precondition: capture ships off",
            configStore.automaticCaptureEnabled.first(),
        )
        val vm = viewModel(configStore)
        advanceUntilIdle()

        vm.linkExistingScale("E7:DB:51:F1:36:91", "2", "1234")
        advanceUntilIdle()

        assertTrue(configStore.automaticCaptureEnabled.first())
    }

    @Test
    fun aRejectedLinkDoesNotEnableAutomaticCapture() = runTest {
        val configStore = FakeConfigStore()
        val vm = viewModel(configStore)
        advanceUntilIdle()

        // Consent code outside SigWeightProfile.CONSENT_CODE_RANGE — the
        // validation branch, which must not reach the success helper.
        vm.linkExistingScale("E7:DB:51:F1:36:91", "2", "99999999")
        advanceUntilIdle()

        assertFalse(configStore.automaticCaptureEnabled.first())
        assertTrue(vm.uiState.value.scaleRegistration is ScaleRegistrationUiState.Failure)
    }
}
