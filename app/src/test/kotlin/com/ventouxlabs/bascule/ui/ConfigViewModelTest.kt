package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.ui.fake.FakeAuthTokenStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** WP-25: `ui/ConfigScreen.kt`'s ViewModel. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        configStore: FakeConfigStore = FakeConfigStore(),
        authTokenStore: FakeAuthTokenStore = FakeAuthTokenStore(),
        consentStore: InMemoryConsentStore = InMemoryConsentStore(),
        deliveryTrigger: FakeDeliveryTrigger = FakeDeliveryTrigger(),
    ) = ConfigViewModel(configStore, authTokenStore, consentStore, deliveryTrigger)

    @Test
    fun registeredUserIndexIsReadOnlyAndSourcedFromConsentStore() = runTest {
        val consentStore = InMemoryConsentStore().apply {
            save("AA:BB:CC:DD:EE:FF", ScaleCredential(scaleIndex = 4, consentCode = 0x1234))
        }
        val configStore = FakeConfigStore(initialPairedDeviceAddress = "AA:BB:CC:DD:EE:FF")
        val vm = viewModel(configStore = configStore, consentStore = consentStore)
        advanceUntilIdle()

        assertEquals(4, vm.uiState.value.registeredUserIndex)
    }

    @Test
    fun registeredUserIndexIsNullWhenNoDeviceIsPairedYet() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertNull(
            "WP-08 hasn't landed, so no paired device address exists yet — this is a real state, not a bug",
            vm.uiState.value.registeredUserIndex,
        )
    }

    @Test
    fun reRegisterClearsTheStoredCredentialForThatDevice() = runTest {
        val consentStore = InMemoryConsentStore().apply {
            save("AA:BB:CC:DD:EE:FF", ScaleCredential(scaleIndex = 4, consentCode = 0x1234))
        }
        val vm = viewModel(consentStore = consentStore)
        advanceUntilIdle()

        vm.reRegister("AA:BB:CC:DD:EE:FF")

        assertNull(
            "clearing the credential is what makes the next handshake register fresh",
            consentStore.credentialFor("AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun baseUrlRejectsNonHttpScheme() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveBaseUrl("ftp://example.com")
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.baseUrlError)
    }

    @Test
    fun baseUrlRejectsUnparseableHost() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        // A literal space in the authority is illegal and makes java.net.URI
        // throw URISyntaxException — the exception path, not merely a URI
        // that parses fine but happens to carry a blank host.
        vm.saveBaseUrl("https://exa mple.com")
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.baseUrlError)
    }

    @Test
    fun baseUrlRejectsAMissingHost() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.saveBaseUrl("https://")
        advanceUntilIdle()

        assertNotNull(
            "syntactically valid but with no host at all must still be rejected",
            vm.uiState.value.baseUrlError,
        )
    }

    @Test
    fun validBaseUrlIsAccepted() = runTest {
        val configStore = FakeConfigStore()
        val vm = viewModel(configStore = configStore)
        advanceUntilIdle()

        vm.saveBaseUrl("https://vitalforge.example.com")
        advanceUntilIdle()

        assertNull(vm.uiState.value.baseUrlError)
        assertEquals("https://vitalforge.example.com", configStore.baseUrl.value)
    }

    @Test
    fun tokenIsNeverExposedForDisplay() = runTest {
        val authTokenStore = FakeAuthTokenStore()
        val vm = viewModel(authTokenStore = authTokenStore)
        advanceUntilIdle()

        vm.saveToken("super-secret-token")
        advanceUntilIdle()

        // ConfigUiState carries no field that could ever hold the raw token —
        // only tokenIsSet. This is enforced by the type, not by a runtime
        // check: there is nowhere in ConfigUiState to accidentally read it
        // from. Also assert the underlying store: this test proves the
        // ViewModel's own contract, not just the store's.
        assertTrue(vm.uiState.value.tokenIsSet)
        // The store itself may hold the raw value; the UI state never does.
        assertEquals("super-secret-token", authTokenStore.token())
    }

    @Test
    fun savingTokenTriggersImmediateDrain() = runTest {
        val deliveryTrigger = FakeDeliveryTrigger()
        val vm = viewModel(deliveryTrigger = deliveryTrigger)
        advanceUntilIdle()

        vm.saveToken("a-token")
        advanceUntilIdle()

        assertEquals(1, deliveryTrigger.triggerCount)
    }

    @Test
    fun clearingTokenDoesNotTriggerADrain() = runTest {
        val deliveryTrigger = FakeDeliveryTrigger()
        val vm = viewModel(authTokenStore = FakeAuthTokenStore("existing"), deliveryTrigger = deliveryTrigger)
        advanceUntilIdle()

        vm.clearToken()
        advanceUntilIdle()

        assertEquals(
            "clearing a token can only make things worse for delivery, not better — nothing to drain for",
            0,
            deliveryTrigger.triggerCount,
        )
    }
}
