package com.ventouxlabs.bascule.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.delivery.DeliveryTrigger
import com.ventouxlabs.bascule.network.AuthTokenStore
import com.ventouxlabs.bascule.network.ConnectionTestResult
import com.ventouxlabs.bascule.network.ContractVersion
import com.ventouxlabs.bascule.network.LoginResult
import com.ventouxlabs.bascule.network.SessionCookieStore
import com.ventouxlabs.bascule.network.V1Shaper
import com.ventouxlabs.bascule.network.VitalForgeApi
import com.ventouxlabs.bascule.network.VitalForgeHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URISyntaxException

data class ConfigUiState(
    val baseUrl: String = "",
    val displayUnit: WeightUnit = WeightUnit.KILOGRAMS,
    val contractVersion: ContractVersion = ContractVersion.V1_WEIGHT_ONLY,
    val alwaysOnBridging: Boolean = false,
    val tokenIsSet: Boolean = false,
    val sessionIsSet: Boolean = false,
    val loginError: String? = null,
    val isLoggingIn: Boolean = false,
    val pairedDeviceAddress: String? = null,
    val registeredUserIndex: Int? = null,
    val baseUrlError: String? = null,
    val connectionTest: ConnectionTestUiState = ConnectionTestUiState.Idle,
)

sealed interface ConnectionTestUiState {
    data object Idle : ConnectionTestUiState
    data object Testing : ConnectionTestUiState
    data object Success : ConnectionTestUiState
    data class Failure(val message: String) : ConnectionTestUiState
}

private data class StoredConfig(
    val baseUrl: String?,
    val displayUnit: WeightUnit,
    val contractVersion: ContractVersion,
    val alwaysOnBridging: Boolean,
    val pairedDeviceAddress: String?,
)

private data class TransientUiState(
    val baseUrlError: String?,
    val connectionTest: ConnectionTestUiState,
    val loginError: String?,
    val isLoggingIn: Boolean,
)

/**
 * WP-25: §5's config surface. The registered user index is read-only,
 * sourced from [ConsentStore] via [ConfigStore.pairedDeviceAddress]
 * (O-08.5) — never a config field the user typed. It resolves to null until
 * WP-08's scan/session layer starts recording which device this app is
 * actually paired with; that is a real "not registered yet" state, not a bug
 * in this package.
 *
 * The permission-request flow itself lives in [PermissionRequester]
 * (SDK-branched pure logic) plus this screen's own launcher plumbing — this
 * ViewModel only exposes the state the screen renders around it.
 */
class ConfigViewModel(
    private val configStore: ConfigStore,
    private val authTokenStore: AuthTokenStore,
    private val consentStore: ConsentStore,
    private val sessionCookieStore: SessionCookieStore,
    private val deliveryTrigger: DeliveryTrigger,
    private val dao: ReadingDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Contract/shaper are irrelevant to [VitalForgeApi.testConnection]/[VitalForgeApi.login]
     * (neither calls `shape()`), so they are hardcoded here rather than threaded through
     * from the user's saved contract version — nothing reads them for these calls.
     */
    private val apiFactory: (baseUrl: String) -> VitalForgeApi = { baseUrl ->
        VitalForgeHttpClient(
            baseUrl = baseUrl,
            tokenProvider = authTokenStore::token,
            contract = ContractVersion.V1_WEIGHT_ONLY,
            shaper = V1Shaper,
            sessionCookieProvider = sessionCookieStore::cookie,
        )
    },
) : ViewModel() {

    private val _baseUrlError = MutableStateFlow<String?>(null)

    /** Bumped by [saveToken], [clearCredentials], and a successful [login] — whichever credential store changed. */
    private val _credentialVersion = MutableStateFlow(0)

    /** Bumped by [reRegister] — [ConsentStore] has no Flow of its own, so this tells [uiState] to re-read it. */
    private val _consentVersion = MutableStateFlow(0)
    private val _connectionTest = MutableStateFlow<ConnectionTestUiState>(ConnectionTestUiState.Idle)
    private val _loginError = MutableStateFlow<String?>(null)
    private val _isLoggingIn = MutableStateFlow(false)
    private val _loginSucceeded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** One-shot — a sticky boolean would re-fire on every tab-switch return via saveState/restoreState. */
    val loginSucceeded: SharedFlow<Unit> = _loginSucceeded.asSharedFlow()

    private val storedConfig = combine(
        configStore.baseUrl,
        configStore.displayUnit,
        configStore.contractVersion,
        configStore.alwaysOnBridging,
        configStore.pairedDeviceAddress,
    ) { baseUrl, displayUnit, contractVersion, alwaysOn, pairedAddress ->
        StoredConfig(baseUrl, displayUnit, contractVersion, alwaysOn, pairedAddress)
    }

    /** combine() tops out at 5 typed flows per call — this nests to fit the login/connection-test additions. */
    private val transientState = combine(
        _baseUrlError,
        _connectionTest,
        _loginError,
        _isLoggingIn,
    ) { urlError, connectionTest, loginError, isLoggingIn ->
        TransientUiState(urlError, connectionTest, loginError, isLoggingIn)
    }

    val uiState: StateFlow<ConfigUiState> = combine(
        storedConfig,
        transientState,
        _credentialVersion,
        _consentVersion,
    ) { stored, transient, _, _ ->
        // authTokenStore.isSet(), sessionCookieStore.isSet(), and
        // consentStore.credentialFor() are synchronous EncryptedSharedPreferences
        // reads — flowOn(IO) below keeps them off the collecting (Main) dispatcher.
        ConfigUiState(
            baseUrl = stored.baseUrl.orEmpty(),
            displayUnit = stored.displayUnit,
            contractVersion = stored.contractVersion,
            alwaysOnBridging = stored.alwaysOnBridging,
            tokenIsSet = authTokenStore.isSet(),
            sessionIsSet = sessionCookieStore.isSet(),
            loginError = transient.loginError,
            isLoggingIn = transient.isLoggingIn,
            pairedDeviceAddress = stored.pairedDeviceAddress,
            registeredUserIndex = stored.pairedDeviceAddress?.let { consentStore.credentialFor(it)?.scaleIndex },
            baseUrlError = transient.baseUrlError,
            connectionTest = transient.connectionTest,
        )
    }.flowOn(ioDispatcher).stateIn(viewModelScope, SharingStarted.Eagerly, ConfigUiState())

    /** Clears a stale validation error as soon as the user starts editing again, rather than leaving it until Save. */
    fun onBaseUrlTextChanged() {
        if (_baseUrlError.value != null) _baseUrlError.value = null
    }

    fun saveBaseUrl(url: String) {
        val error = validateBaseUrl(url)
        _baseUrlError.value = error
        if (error == null) {
            viewModelScope.launch { configStore.saveBaseUrl(url) }
        }
    }

    fun saveDisplayUnit(unit: WeightUnit) {
        viewModelScope.launch { configStore.saveDisplayUnit(unit) }
    }

    fun saveContractVersion(version: ContractVersion) {
        viewModelScope.launch { configStore.saveContractVersion(version) }
    }

    fun saveAlwaysOnBridging(enabled: Boolean) {
        viewModelScope.launch { configStore.saveAlwaysOnBridging(enabled) }
    }

    /**
     * §8.6: saving a new token flips every `BLOCKED_AUTH` row back to
     * `PENDING` *and* triggers an immediate drain, rather than waiting for
     * the delivery worker's own periodic schedule — the flip alone would
     * leave the rows stuck until that schedule next runs, and the drain
     * alone would find nothing, since the drain query only ever selects
     * `PENDING` rows.
     *
     * `DeliveryWorker.doWork` is itself still a WP-21 stub — this enqueue is
     * correct and will run for real the moment that lands, rather than
     * needing its own follow-up wiring then.
     */
    fun saveToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        authTokenStore.save(trimmed)
        // Mutual exclusion: a token and a session cookie are never both active.
        sessionCookieStore.clear()
        _credentialVersion.value++
        viewModelScope.launch { dao.unblockAuthRows(nowMillis()) }
        deliveryTrigger.triggerImmediateDrain()
    }

    /** Clears whichever credential is active — mutual exclusion means at most one ever is. */
    fun clearCredentials() {
        authTokenStore.clear()
        sessionCookieStore.clear()
        _credentialVersion.value++
    }

    /**
     * Exchanges a username/password for a session cookie (`shared/auth.py`'s
     * `/auth/login` — VitalForge has no per-user token, so this is a second,
     * independent credential type, not a way to obtain the bearer token).
     * Guards against a second tap re-firing the request while the first is
     * still in flight, mirroring [testConnection]'s guard.
     */
    fun login(username: String, password: String) {
        if (_isLoggingIn.value) return
        val trimmedUser = username.trim()
        val trimmedPass = password.trim()
        if (trimmedUser.isEmpty() || trimmedPass.isEmpty()) {
            _loginError.value = "Enter a username and password"
            return
        }
        _isLoggingIn.value = true
        _loginError.value = null
        viewModelScope.launch {
            when (val result = apiFactory(uiState.value.baseUrl).login(trimmedUser, trimmedPass)) {
                is LoginResult.Success -> {
                    sessionCookieStore.save(result.sessionCookie)
                    // Mutual exclusion: a token and a session cookie are never both active.
                    authTokenStore.clear()
                    _credentialVersion.value++
                    _loginSucceeded.emit(Unit)
                }
                LoginResult.InvalidCredentials -> _loginError.value = "Invalid username or password"
                is LoginResult.Unreachable -> _loginError.value = result.reason
            }
            _isLoggingIn.value = false
        }
    }

    /**
     * Read-only — never submits a reading. Guards against a second tap
     * re-firing the request while the first is still in flight, mirroring
     * `ManualEntryViewModel.save()`'s `isSaving` guard.
     */
    fun testConnection() {
        if (_connectionTest.value == ConnectionTestUiState.Testing) return
        _connectionTest.value = ConnectionTestUiState.Testing
        viewModelScope.launch {
            _connectionTest.value = when (val result = apiFactory(uiState.value.baseUrl).testConnection()) {
                ConnectionTestResult.Authorized -> ConnectionTestUiState.Success
                is ConnectionTestResult.Unauthorized ->
                    ConnectionTestUiState.Failure("Server rejected the token (HTTP ${result.httpCode})")
                is ConnectionTestResult.Unreachable -> ConnectionTestUiState.Failure(result.reason)
            }
        }
    }

    /**
     * O-08.5: re-registering may consume one of the scale's 8 profile slots
     * (§8.8, HW-26) — the caller must have already shown that warning and
     * gotten explicit confirmation before this runs. Clearing the stored
     * credential is what makes the next session's handshake register fresh
     * (`BeurerDecoder.beginHandshake`'s no-stored-credential branch).
     */
    fun reRegister(deviceAddress: String) {
        consentStore.clear(deviceAddress)
        _consentVersion.value++
    }

    companion object {
        fun validateBaseUrl(url: String): String? {
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                return "Not a valid URL"
            }
            // https only: the manifest declares no cleartext-traffic policy,
            // so on API 28+ a saved http:// URL would validate fine here and
            // then fail at request time with no way for the user to tell why.
            if (uri.scheme != "https") return "URL must start with https://"
            if (uri.host.isNullOrBlank()) return "URL must include a host"
            return null
        }

        fun factory(app: BasculeApplication) = viewModelFactory {
            initializer {
                ConfigViewModel(
                    configStore = app.configStore,
                    authTokenStore = app.authTokenStore,
                    consentStore = app.consentStore,
                    sessionCookieStore = app.sessionCookieStore,
                    deliveryTrigger = app.deliveryTrigger,
                    dao = app.database.readingDao(),
                )
            }
        }
    }
}
