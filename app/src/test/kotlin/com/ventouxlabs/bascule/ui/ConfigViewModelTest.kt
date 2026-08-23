package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.ui.fake.FakeAuthTokenStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import com.ventouxlabs.bascule.ui.fake.readingFixture
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
        dao: FakeReadingDao = FakeReadingDao(),
    ) = ConfigViewModel(
        configStore,
        authTokenStore,
        consentStore,
        deliveryTrigger,
        dao,
        ioDispatcher = mainDispatcherRule.dispatcher,
    )

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

    /**
     * Regression test: the ViewModel's own state must reflect a re-register,
     * not merely the underlying store. `reRegister` used to clear the
     * consent store without invalidating anything `uiState`'s `combine`
     * depends on, so the screen kept showing the stale index and kept
     * offering the re-register button after it had already fired.
     */
    @Test
    fun reRegisterUpdatesUiStateRegisteredUserIndexToNull() = runTest {
        val consentStore = InMemoryConsentStore().apply {
            save("AA:BB:CC:DD:EE:FF", ScaleCredential(scaleIndex = 4, consentCode = 0x1234))
        }
        val configStore = FakeConfigStore(initialPairedDeviceAddress = "AA:BB:CC:DD:EE:FF")
        val vm = viewModel(configStore = configStore, consentStore = consentStore)
        advanceUntilIdle()
        assertEquals(4, vm.uiState.value.registeredUserIndex)

        vm.reRegister("AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        assertNull(
            "the screen must stop claiming registration and stop offering the button after firing it",
            vm.uiState.value.registeredUserIndex,
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
    fun baseUrlRejectsPlainHttp() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        // The manifest declares no cleartext-traffic policy, so a saved
        // http:// URL would validate here and then fail at request time
        // with no way for the user to tell why — https is required outright.
        vm.saveBaseUrl("http://example.com")
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

    /**
     * What "never exposed for display" actually rests on is
     * [ConfigUiState]'s own field set — it declares no property that could
     * hold the raw value, so there is nothing for a Composable to
     * accidentally read regardless of what this test asserts. What this test
     * *can* verify at runtime: `saveToken` actually persists the value it
     * was given and flips `tokenIsSet`, which the type-level guarantee alone
     * doesn't prove.
     */
    @Test
    fun saveTokenPersistsTheValueAndSetsTokenIsSetTrue() = runTest {
        val authTokenStore = FakeAuthTokenStore()
        val vm = viewModel(authTokenStore = authTokenStore)
        advanceUntilIdle()

        vm.saveToken("super-secret-token")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.tokenIsSet)
        assertEquals("super-secret-token", authTokenStore.token())
    }

    @Test
    fun saveTokenTrimsSurroundingWhitespace() = runTest {
        val authTokenStore = FakeAuthTokenStore()
        val vm = viewModel(authTokenStore = authTokenStore)
        advanceUntilIdle()

        // A pasted token with a trailing newline would otherwise be stored
        // verbatim, fail auth with a confusing 401, and pause the whole
        // delivery drain over an invisible whitespace character.
        vm.saveToken("  a-token\n")
        advanceUntilIdle()

        assertEquals("a-token", authTokenStore.token())
    }

    @Test
    fun saveTokenRejectsBlankAfterTrimming() = runTest {
        val authTokenStore = FakeAuthTokenStore()
        val deliveryTrigger = FakeDeliveryTrigger()
        val vm = viewModel(authTokenStore = authTokenStore, deliveryTrigger = deliveryTrigger)
        advanceUntilIdle()

        vm.saveToken("   ")
        advanceUntilIdle()

        assertNull(authTokenStore.token())
        assertEquals("a blank token must not trigger a drain either", 0, deliveryTrigger.triggerCount)
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

    /**
     * §8.6: saving a token must flip every `BLOCKED_AUTH` row back to
     * `PENDING`, not only trigger a drain — the drain query only ever
     * selects `PENDING` rows, so triggering it alone would find nothing and
     * the blocked rows would stay blocked forever.
     */
    @Test
    fun savingTokenUnblocksBlockedAuthRows() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "blocked1", status = ReadingStatus.BLOCKED_AUTH, attemptCount = 3))
        dao.insert(readingFixture(id = "blocked2", status = ReadingStatus.BLOCKED_AUTH))
        dao.insert(readingFixture(id = "sent", status = ReadingStatus.SENT))
        val vm = viewModel(dao = dao)
        advanceUntilIdle()

        vm.saveToken("a-token")
        advanceUntilIdle()

        assertEquals(ReadingStatus.PENDING, dao.rows.value.single { it.id == "blocked1" }.status)
        assertEquals(0, dao.rows.value.single { it.id == "blocked1" }.attemptCount)
        assertEquals(ReadingStatus.PENDING, dao.rows.value.single { it.id == "blocked2" }.status)
        assertEquals(
            "an already-SENT row must not be touched by the unblock",
            ReadingStatus.SENT,
            dao.rows.value.single { it.id == "sent" }.status,
        )
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
