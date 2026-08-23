package com.ventouxlabs.bascule.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ventouxlabs.bascule.network.ContractVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "bascule_config")

/**
 * Non-secret configuration (`00-design.md` §5): base URL, display unit,
 * contract version, always-on bridging toggle, and the address of the one
 * scale this app is paired with. The last of those is not yet populated by
 * anything — WP-08's scan/session layer, which would discover and remember
 * it, is unimplemented — but the slot exists now so ConfigScreen's read-only
 * registered-index display (WP-25) has somewhere real to read from the
 * moment WP-08 does write it, rather than needing its own follow-up wiring.
 *
 * Never holds the VitalForge token — that stays in
 * [com.ventouxlabs.bascule.network.AuthTokenStore]'s encrypted storage, a
 * deliberately separate store for a deliberately different secrecy class.
 *
 * An interface, mirroring [com.ventouxlabs.bascule.ble.session.ConsentStore],
 * so ViewModels can be unit-tested against a fake rather than needing a real
 * `Context`/DataStore.
 */
interface ConfigStore {
    val baseUrl: Flow<String?>
    val displayUnit: Flow<WeightUnit>
    val contractVersion: Flow<ContractVersion>
    val alwaysOnBridging: Flow<Boolean>
    val pairedDeviceAddress: Flow<String?>

    suspend fun saveBaseUrl(url: String)
    suspend fun saveDisplayUnit(unit: WeightUnit)
    suspend fun saveContractVersion(version: ContractVersion)
    suspend fun saveAlwaysOnBridging(enabled: Boolean)
    suspend fun savePairedDeviceAddress(address: String)
}

class DataStoreConfigStore(context: Context) : ConfigStore {

    private val store = context.applicationContext.configDataStore

    override val baseUrl: Flow<String?> = store.data.map { it[BASE_URL] }

    override val displayUnit: Flow<WeightUnit> = store.data.map { prefs ->
        prefs[DISPLAY_UNIT]?.let { runCatching { WeightUnit.valueOf(it) }.getOrNull() } ?: WeightUnit.KILOGRAMS
    }

    override val contractVersion: Flow<ContractVersion> = store.data.map { prefs ->
        prefs[CONTRACT_VERSION]?.let { runCatching { ContractVersion.valueOf(it) }.getOrNull() }
            ?: ContractVersion.V1_WEIGHT_ONLY
    }

    override val alwaysOnBridging: Flow<Boolean> = store.data.map { it[ALWAYS_ON_BRIDGING] ?: false }

    override val pairedDeviceAddress: Flow<String?> = store.data.map { it[PAIRED_DEVICE_ADDRESS] }

    override suspend fun saveBaseUrl(url: String) {
        store.edit { it[BASE_URL] = url }
    }

    override suspend fun saveDisplayUnit(unit: WeightUnit) {
        store.edit { it[DISPLAY_UNIT] = unit.name }
    }

    override suspend fun saveContractVersion(version: ContractVersion) {
        store.edit { it[CONTRACT_VERSION] = version.name }
    }

    override suspend fun saveAlwaysOnBridging(enabled: Boolean) {
        store.edit { it[ALWAYS_ON_BRIDGING] = enabled }
    }

    override suspend fun savePairedDeviceAddress(address: String) {
        store.edit { it[PAIRED_DEVICE_ADDRESS] = address }
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val DISPLAY_UNIT = stringPreferencesKey("display_unit")
        val CONTRACT_VERSION = stringPreferencesKey("contract_version")
        val ALWAYS_ON_BRIDGING = booleanPreferencesKey("always_on_bridging")
        val PAIRED_DEVICE_ADDRESS = stringPreferencesKey("paired_device_address")
    }
}
