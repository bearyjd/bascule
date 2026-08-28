package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.ble.RegistrationPhase
import com.ventouxlabs.bascule.ble.ScaleRegistrar
import com.ventouxlabs.bascule.ble.ScaleRegistrationResult
import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.data.BackupCredentialType
import com.ventouxlabs.bascule.data.PortableSettings
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.data.ScaleProfileStore
import com.ventouxlabs.bascule.data.SettingsBackupCodec
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.network.ConnectionTestResult
import com.ventouxlabs.bascule.network.LoginResult
import com.ventouxlabs.bascule.ui.fake.FakeAuthTokenStore
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.FakeSessionCookieStore
import com.ventouxlabs.bascule.ui.fake.FakeVitalForgeApi
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import com.ventouxlabs.bascule.ui.fake.readingFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

    /**
     * `uiState` shares with `WhileSubscribed`, so `stateIn` never starts the
     * upstream until something collects it. Without the `backgroundScope`
     * collector every assertion against `uiState.value` would read
     * [ConfigUiState]'s defaults rather than the ViewModel's actual state.
     */
    private fun TestScope.viewModel(
        configStore: FakeConfigStore = FakeConfigStore(),
        authTokenStore: FakeAuthTokenStore = FakeAuthTokenStore(),
        consentStore: ConsentStore = InMemoryConsentStore(),
        sessionCookieStore: FakeSessionCookieStore = FakeSessionCookieStore(),
        deliveryTrigger: FakeDeliveryTrigger = FakeDeliveryTrigger(),
        dao: FakeReadingDao = FakeReadingDao(),
        vitalForgeApi: FakeVitalForgeApi = FakeVitalForgeApi(),
        scaleRegistrar: ScaleRegistrar? = null,
        scaleProfileStore: ScaleProfileStore? = null,
        rearmScanner: (suspend () -> Unit)? = null,
    ): ConfigViewModel {
        val vm = ConfigViewModel(
            configStore,
            authTokenStore,
            consentStore,
            sessionCookieStore,
            deliveryTrigger,
            dao,
            ioDispatcher = mainDispatcherRule.dispatcher,
            scaleRegistrar = scaleRegistrar,
            scaleProfileStore = scaleProfileStore,
            rearmScanner = rearmScanner,
            apiFactory = { vitalForgeApi },
        )
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

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
    fun unavailableReRegistrationPreservesTheStoredCredential() = runTest {
        val consentStore = InMemoryConsentStore().apply {
            save("AA:BB:CC:DD:EE:FF", ScaleCredential(scaleIndex = 4, consentCode = 0x1234))
        }
        val vm = viewModel(consentStore = consentStore)
        advanceUntilIdle()

        vm.reRegister()

        assertNotNull(
            "do not burn the working mapping until a scale has actually been found",
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
    fun unavailableReRegistrationKeepsTheRegisteredUserIndex() = runTest {
        val consentStore = InMemoryConsentStore().apply {
            save("AA:BB:CC:DD:EE:FF", ScaleCredential(scaleIndex = 4, consentCode = 0x1234))
        }
        val configStore = FakeConfigStore(initialPairedDeviceAddress = "AA:BB:CC:DD:EE:FF")
        val vm = viewModel(configStore = configStore, consentStore = consentStore)
        advanceUntilIdle()
        assertEquals(4, vm.uiState.value.registeredUserIndex)

        vm.reRegister()
        advanceUntilIdle()

        assertEquals(4, vm.uiState.value.registeredUserIndex)
        assertTrue(vm.uiState.value.scaleRegistration is ScaleRegistrationUiState.Failure)
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

        vm.clearCredentials()
        advanceUntilIdle()

        assertEquals(
            "clearing a token can only make things worse for delivery, not better — nothing to drain for",
            0,
            deliveryTrigger.triggerCount,
        )
    }

    @Test
    fun testConnectionSurfacesSuccessOnAuthorized() = runTest {
        val vitalForgeApi = FakeVitalForgeApi(connectionResult = ConnectionTestResult.Authorized)
        val vm = viewModel(vitalForgeApi = vitalForgeApi)
        advanceUntilIdle()

        vm.testConnection()
        advanceUntilIdle()

        assertEquals(ConnectionTestUiState.Success, vm.uiState.value.connectionTest)
    }

    @Test
    fun testConnectionSurfacesUnauthorizedAsAFailureMessage() = runTest {
        val vitalForgeApi = FakeVitalForgeApi(connectionResult = ConnectionTestResult.Unauthorized(401))
        val vm = viewModel(vitalForgeApi = vitalForgeApi)
        advanceUntilIdle()

        vm.testConnection()
        advanceUntilIdle()

        val result = vm.uiState.value.connectionTest
        assertTrue(result is ConnectionTestUiState.Failure)
        assertEquals(
            "the user needs to know it was the credential, not the network, that failed",
            "Server rejected the credential (HTTP 401)",
            (result as ConnectionTestUiState.Failure).message,
        )
    }

    @Test
    fun testConnectionSurfacesUnreachableReasonVerbatim() = runTest {
        val vitalForgeApi = FakeVitalForgeApi(connectionResult = ConnectionTestResult.Unreachable("network error"))
        val vm = viewModel(vitalForgeApi = vitalForgeApi)
        advanceUntilIdle()

        vm.testConnection()
        advanceUntilIdle()

        val result = vm.uiState.value.connectionTest
        assertTrue(result is ConnectionTestUiState.Failure)
        assertEquals("network error", (result as ConnectionTestUiState.Failure).message)
    }

    @Test
    fun secondTapWhileTestingIsIgnored() = runTest {
        val vitalForgeApi = FakeVitalForgeApi()
        val vm = viewModel(vitalForgeApi = vitalForgeApi)
        advanceUntilIdle()

        vm.testConnection()
        vm.testConnection() // connectionTest is set to Testing synchronously before the first coroutine ever runs
        advanceUntilIdle()

        assertEquals(
            "two taps before the first check completes must not fire two requests",
            1,
            vitalForgeApi.testConnectionCallCount,
        )
    }

    @Test
    fun loginSavesTheSessionCookieAndClearsAnyStoredToken() = runTest {
        val sessionCookieStore = FakeSessionCookieStore()
        val authTokenStore = FakeAuthTokenStore("existing-token")
        val vitalForgeApi = FakeVitalForgeApi(loginResult = LoginResult.Success("session-cookie-value"))
        val vm = viewModel(
            authTokenStore = authTokenStore,
            sessionCookieStore = sessionCookieStore,
            vitalForgeApi = vitalForgeApi,
        )
        advanceUntilIdle()

        vm.login("alice", "hunter2")
        advanceUntilIdle()

        assertEquals("session-cookie-value", sessionCookieStore.cookie())
        assertTrue(vm.uiState.value.sessionIsSet)
        assertEquals(
            "mutual exclusion: a successful login must clear any previously stored token",
            null,
            authTokenStore.token(),
        )
    }

    @Test
    fun loginPreservesPasswordWhitespace() = runTest {
        val vitalForgeApi = FakeVitalForgeApi(loginResult = LoginResult.Success("session-cookie-value"))
        val vm = viewModel(vitalForgeApi = vitalForgeApi)
        advanceUntilIdle()

        vm.login("  alice  ", "  meaningful password  ")
        advanceUntilIdle()

        assertEquals(
            "passwords are opaque credentials and must be submitted exactly as entered",
            "  meaningful password  ",
            vitalForgeApi.lastLoginPassword,
        )
    }

    @Test
    fun successfulLoginUnblocksAuthRowsAndTriggersImmediateDrain() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "blocked", status = ReadingStatus.BLOCKED_AUTH, attemptCount = 3))
        val deliveryTrigger = FakeDeliveryTrigger()
        val vitalForgeApi = FakeVitalForgeApi(loginResult = LoginResult.Success("session-cookie-value"))
        val vm = viewModel(
            dao = dao,
            deliveryTrigger = deliveryTrigger,
            vitalForgeApi = vitalForgeApi,
        )
        advanceUntilIdle()

        vm.login("alice", "hunter2")
        advanceUntilIdle()

        assertEquals(ReadingStatus.PENDING, dao.rows.value.single().status)
        assertEquals(0, dao.rows.value.single().attemptCount)
        assertEquals(
            "a newly usable session must retry rows rejected under the old credential immediately",
            1,
            deliveryTrigger.triggerCount,
        )
    }

    @Test
    fun savingATokenClearsAnyStoredSessionCookie() = runTest {
        val sessionCookieStore = FakeSessionCookieStore("existing-cookie")
        val vm = viewModel(sessionCookieStore = sessionCookieStore)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.sessionIsSet)

        vm.saveToken("a-token")
        advanceUntilIdle()

        assertEquals(
            "mutual exclusion, the other direction: saving a token must clear any stored session cookie",
            null,
            sessionCookieStore.cookie(),
        )
        assertTrue(vm.uiState.value.tokenIsSet)
        assertTrue(
            "the screen must stop claiming a cookie-based session once a token has taken over",
            !vm.uiState.value.sessionIsSet,
        )
    }

    @Test
    fun loginSurfacesInvalidCredentialsAsAFailureMessage() = runTest {
        val vitalForgeApi = FakeVitalForgeApi(loginResult = LoginResult.InvalidCredentials)
        val vm = viewModel(vitalForgeApi = vitalForgeApi)
        advanceUntilIdle()

        vm.login("alice", "wrong-password")
        advanceUntilIdle()

        assertEquals("Invalid username or password", vm.uiState.value.loginError)
        assertTrue(
            "an invalid login must not be reported as signed in",
            !vm.uiState.value.sessionIsSet,
        )
    }

    @Test
    fun loginRejectsBlankUsernameOrPassword() = runTest {
        val vitalForgeApi = FakeVitalForgeApi()
        val vm = viewModel(vitalForgeApi = vitalForgeApi)
        advanceUntilIdle()

        vm.login("", "hunter2")
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.loginError)
        assertEquals(
            "a blank username must not reach the network at all",
            0,
            vitalForgeApi.loginCallCount,
        )
    }

    @Test
    fun secondLoginTapWhileInFlightIsIgnored() = runTest {
        val vitalForgeApi = FakeVitalForgeApi()
        val vm = viewModel(vitalForgeApi = vitalForgeApi)
        advanceUntilIdle()

        vm.login("alice", "hunter2")
        vm.login("alice", "hunter2") // isLoggingIn is set synchronously before the first coroutine ever runs
        advanceUntilIdle()

        assertEquals(
            "two taps before the first login completes must not fire two requests",
            1,
            vitalForgeApi.loginCallCount,
        )
    }

    @Test
    fun clearCredentialsClearsBothStores() = runTest {
        val authTokenStore = FakeAuthTokenStore("a-token")
        val sessionCookieStore = FakeSessionCookieStore("a-cookie")
        val vm = viewModel(authTokenStore = authTokenStore, sessionCookieStore = sessionCookieStore)
        advanceUntilIdle()

        vm.clearCredentials()
        advanceUntilIdle()

        assertEquals(null, authTokenStore.token())
        assertEquals(null, sessionCookieStore.cookie())
        assertTrue(!vm.uiState.value.tokenIsSet)
        assertTrue(!vm.uiState.value.sessionIsSet)
    }

    @Test
    fun scaleRegistrationSurfacesSuccess() = runTest {
        val registrar = object : ScaleRegistrar {
            override suspend fun register(
                forceNew: Boolean,
                onPhase: (RegistrationPhase) -> Unit,
            ): ScaleRegistrationResult {
                onPhase(RegistrationPhase.SCANNING)
                onPhase(RegistrationPhase.CONNECTING)
                return ScaleRegistrationResult.Success("E7:DB:51:F1:36:91", 2)
            }
        }
        val vm = viewModel(scaleRegistrar = registrar)
        advanceUntilIdle()

        vm.startScaleRegistration()
        advanceUntilIdle()

        assertEquals(
            ScaleRegistrationUiState.Success("E7:DB:51:F1:36:91", 2),
            vm.uiState.value.scaleRegistration,
        )
    }

    @Test
    fun linkingExistingScaleRestoresMappingWithoutRunningRegistrar() = runTest {
        val configStore = FakeConfigStore()
        val consentStore = InMemoryConsentStore()
        val vm = viewModel(configStore = configStore, consentStore = consentStore)
        advanceUntilIdle()

        vm.linkExistingScale("e7:db:51:f1:36:91", "2", "1234")
        advanceUntilIdle()

        assertEquals("E7:DB:51:F1:36:91", configStore.pairedDeviceAddress.value)
        assertEquals(ScaleCredential(2, 1234), consentStore.credentialFor("E7:DB:51:F1:36:91"))
        assertEquals(2, vm.uiState.value.registeredUserIndex)
    }

    @Test
    fun settingsExportIncludesCredentialsAndScaleMappingOnlyInsideEncryption() = runTest {
        val configStore = FakeConfigStore(
            initialBaseUrl = "https://weight.grepon.cc",
            initialPairedDeviceAddress = "E7:DB:51:F1:36:91",
        )
        val consentStore = InMemoryConsentStore().apply {
            save("E7:DB:51:F1:36:91", ScaleCredential(2, 1234))
        }
        val vm = viewModel(
            configStore = configStore,
            consentStore = consentStore,
            sessionCookieStore = FakeSessionCookieStore("session-cookie"),
        )
        advanceUntilIdle()

        val bytes = vm.exportSettings("correct horse battery staple").getOrThrow()
        val restored = SettingsBackupCodec.decrypt(bytes, "correct horse battery staple")

        assertEquals("https://weight.grepon.cc", restored.baseUrl)
        assertEquals(BackupCredentialType.SESSION, restored.credentialType)
        assertEquals("session-cookie", restored.credentialValue)
        assertEquals(ScaleCredential(2, 1234), restored.scaleCredential)
    }

    @Test
    fun settingsImportRestoresConfigurationCredentialsAndScaleMapping() = runTest {
        val configStore = FakeConfigStore()
        val consentStore = InMemoryConsentStore()
        val tokenStore = FakeAuthTokenStore()
        val sessionStore = FakeSessionCookieStore("old-session")
        val vm = viewModel(
            configStore = configStore,
            consentStore = consentStore,
            authTokenStore = tokenStore,
            sessionCookieStore = sessionStore,
        )
        val bytes = SettingsBackupCodec.encrypt(
            PortableSettings(
                baseUrl = "https://weight.grepon.cc",
                displayUnit = WeightUnit.POUNDS,
                contractVersion = com.ventouxlabs.bascule.network.ContractVersion.V1_WEIGHT_ONLY,
                alwaysOnBridging = true,
                credentialType = BackupCredentialType.TOKEN,
                credentialValue = "restored-token",
                pairedDeviceAddress = "E7:DB:51:F1:36:91",
                scaleCredential = ScaleCredential(2, 1234),
            ),
            "correct horse battery staple",
        )

        vm.importSettings(bytes, "correct horse battery staple").getOrThrow()
        advanceUntilIdle()

        assertEquals("https://weight.grepon.cc", configStore.baseUrl.value)
        assertEquals(WeightUnit.POUNDS, configStore.displayUnit.value)
        assertEquals("restored-token", tokenStore.token())
        assertEquals(null, sessionStore.cookie())
        assertEquals(ScaleCredential(2, 1234), consentStore.credentialFor("E7:DB:51:F1:36:91"))
        assertEquals(2, vm.uiState.value.registeredUserIndex)
    }

    private fun backupPointingAt(baseUrl: String): ByteArray = SettingsBackupCodec.encrypt(
        PortableSettings(
            baseUrl = baseUrl,
            displayUnit = WeightUnit.KILOGRAMS,
            contractVersion = com.ventouxlabs.bascule.network.ContractVersion.V1_WEIGHT_ONLY,
            alwaysOnBridging = false,
            credentialType = BackupCredentialType.TOKEN,
            credentialValue = "imported-token",
            pairedDeviceAddress = null,
            scaleCredential = null,
        ),
        "correct horse battery staple",
    )

    /**
     * S2: a backup carries both the server and a matching credential, so an
     * immediate drain would ship the whole local backlog to a host the user
     * never chose.
     */
    @Test
    fun importingABackupForADifferentHostDoesNotDrainTheBacklog() = runTest {
        val configStore = FakeConfigStore(initialBaseUrl = "https://mine.example.com")
        val deliveryTrigger = FakeDeliveryTrigger()
        val vm = viewModel(configStore = configStore, deliveryTrigger = deliveryTrigger)

        vm.importSettings(backupPointingAt("https://attacker.example.com"), "correct horse battery staple")
            .getOrThrow()
        advanceUntilIdle()

        assertEquals("https://attacker.example.com", configStore.baseUrl.value)
        assertEquals(
            "a credential swap onto a new host must not flush the backlog to it",
            0,
            deliveryTrigger.triggerCount,
        )
    }

    @Test
    fun importingABackupForTheSameHostStillDrainsTheBacklog() = runTest {
        val configStore = FakeConfigStore(initialBaseUrl = "https://mine.example.com")
        val deliveryTrigger = FakeDeliveryTrigger()
        val vm = viewModel(configStore = configStore, deliveryTrigger = deliveryTrigger)

        vm.importSettings(backupPointingAt("https://mine.example.com/api"), "correct horse battery staple")
            .getOrThrow()
        advanceUntilIdle()

        assertEquals(1, deliveryTrigger.triggerCount)
    }
}
