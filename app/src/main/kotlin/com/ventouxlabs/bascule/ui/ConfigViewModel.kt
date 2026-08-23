package com.ventouxlabs.bascule.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.delivery.DeliveryTrigger
import com.ventouxlabs.bascule.network.AuthTokenStore
import com.ventouxlabs.bascule.network.ContractVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val pairedDeviceAddress: String? = null,
    val registeredUserIndex: Int? = null,
    val baseUrlError: String? = null,
)

private data class StoredConfig(
    val baseUrl: String?,
    val displayUnit: WeightUnit,
    val contractVersion: ContractVersion,
    val alwaysOnBridging: Boolean,
    val pairedDeviceAddress: String?,
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
    private val deliveryTrigger: DeliveryTrigger,
) : ViewModel() {

    private val _baseUrlError = MutableStateFlow<String?>(null)
    private val _tokenVersion = MutableStateFlow(0)

    private val storedConfig = combine(
        configStore.baseUrl,
        configStore.displayUnit,
        configStore.contractVersion,
        configStore.alwaysOnBridging,
        configStore.pairedDeviceAddress,
    ) { baseUrl, displayUnit, contractVersion, alwaysOn, pairedAddress ->
        StoredConfig(baseUrl, displayUnit, contractVersion, alwaysOn, pairedAddress)
    }

    val uiState: StateFlow<ConfigUiState> = combine(storedConfig, _baseUrlError, _tokenVersion) { stored, urlError, _ ->
        ConfigUiState(
            baseUrl = stored.baseUrl.orEmpty(),
            displayUnit = stored.displayUnit,
            contractVersion = stored.contractVersion,
            alwaysOnBridging = stored.alwaysOnBridging,
            tokenIsSet = authTokenStore.isSet(),
            pairedDeviceAddress = stored.pairedDeviceAddress,
            registeredUserIndex = stored.pairedDeviceAddress?.let { consentStore.credentialFor(it)?.scaleIndex },
            baseUrlError = urlError,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConfigUiState())

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
     * §8.6: a freshly-saved token might unblock rows that have been sitting
     * `BLOCKED_AUTH`/backing off since before it was set, so this does not
     * wait for the delivery worker's own periodic schedule.
     *
     * `DeliveryWorker.doWork` is itself still a WP-22 stub — this enqueue is
     * correct and will run for real the moment that lands, rather than
     * needing its own follow-up wiring then.
     */
    fun saveToken(token: String) {
        authTokenStore.save(token)
        _tokenVersion.value++
        deliveryTrigger.triggerImmediateDrain()
    }

    fun clearToken() {
        authTokenStore.clear()
        _tokenVersion.value++
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
    }

    companion object {
        fun validateBaseUrl(url: String): String? {
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                return "Not a valid URL"
            }
            if (uri.scheme !in setOf("http", "https")) return "URL must start with http:// or https://"
            if (uri.host.isNullOrBlank()) return "URL must include a host"
            return null
        }

        fun factory(app: BasculeApplication) = viewModelFactory {
            initializer {
                ConfigViewModel(
                    configStore = app.configStore,
                    authTokenStore = app.authTokenStore,
                    consentStore = app.consentStore,
                    deliveryTrigger = app.deliveryTrigger,
                )
            }
        }
    }
}
