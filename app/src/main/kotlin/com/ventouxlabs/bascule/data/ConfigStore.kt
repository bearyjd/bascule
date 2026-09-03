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
 * scale this app is paired with. That last one is written by the registration
 * path, by manual linking, and by a settings import, and is what
 * ConfigScreen's read-only registered-index display (WP-25) reads from.
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
    val automaticCaptureEnabled: Flow<Boolean>
    val pairedDeviceAddress: Flow<String?>

    /**
     * WP-22 (`01-plan.md`): the [ContractVersion] [ReplayMigrationWorker][com.ventouxlabs.bascule.delivery.ReplayMigrationWorker]
     * last finished a replay pass for. Null means never run. Compared against
     * the current [contractVersion] to decide whether a pass is due -- not a
     * boolean flag, because a *second* contract upgrade must run again even
     * though a first one already has.
     */
    val lastReplayMigrationContractVersion: Flow<ContractVersion?>

    suspend fun saveBaseUrl(url: String)
    suspend fun saveDisplayUnit(unit: WeightUnit)
    suspend fun saveContractVersion(version: ContractVersion)
    suspend fun saveAlwaysOnBridging(enabled: Boolean)
    suspend fun saveAutomaticCaptureEnabled(enabled: Boolean)
    suspend fun savePairedDeviceAddress(address: String?)
    suspend fun saveLastReplayMigrationContractVersion(version: ContractVersion)
}

/**
 * The three states a persisted enum name can be in, mirroring
 * [ScaleProfileCodec.StoredProfiles]. [Unreadable] exists because the other two
 * are not exhaustive, and collapsing it into [Absent] is what makes an L1-class
 * silent revert possible: a stored `ContractVersion` that no longer parses —
 * corrupted, or renamed by a refactor — currently reads back as
 * [ContractVersion.V1_WEIGHT_ONLY], and every body-composition field stops
 * being delivered with nothing telling the user their collection scope shrank.
 *
 * Retaining [raw] keeps that case distinguishable. Nothing consumes the
 * distinction yet — surfacing it needs a [ConfigStore] member and a matching
 * fake, both outside this change — but the classification is now made once,
 * here, rather than being thrown away inside a `runCatching`.
 */
sealed interface StoredEnum<out T> {
    /** Nothing has ever been written. Falling back to the default is correct. */
    data object Absent : StoredEnum<Nothing>

    data class Parsed<out T>(val value: T) : StoredEnum<T>

    /** A value exists and matches no constant of the current build. */
    data class Unreadable(val raw: String) : StoredEnum<Nothing>
}

/**
 * Classifies a persisted enum name without throwing. Matches by [Enum.name]
 * rather than calling `valueOf` in a `runCatching`, so an unknown value is a
 * returned state rather than an exception used for control flow.
 */
internal fun <T : Enum<T>> readStoredEnum(raw: String?, values: List<T>): StoredEnum<T> {
    if (raw == null) return StoredEnum.Absent
    val match = values.firstOrNull { it.name == raw } ?: return StoredEnum.Unreadable(raw)
    return StoredEnum.Parsed(match)
}

internal fun <T> StoredEnum<T>.valueOr(default: T): T =
    if (this is StoredEnum.Parsed) value else default

class DataStoreConfigStore(context: Context) : ConfigStore {

    private val store = context.applicationContext.configDataStore

    override val baseUrl: Flow<String?> = store.data.map { it[BASE_URL] }

    override val displayUnit: Flow<WeightUnit> = store.data.map { prefs ->
        readStoredEnum(prefs[DISPLAY_UNIT], WeightUnit.entries).valueOr(WeightUnit.KILOGRAMS)
    }

    override val contractVersion: Flow<ContractVersion> = store.data.map { prefs ->
        readStoredEnum(prefs[CONTRACT_VERSION], ContractVersion.entries).valueOr(ContractVersion.V1_WEIGHT_ONLY)
    }

    override val alwaysOnBridging: Flow<Boolean> = store.data.map { it[ALWAYS_ON_BRIDGING] ?: false }
    override val automaticCaptureEnabled: Flow<Boolean> = store.data.map { it[AUTOMATIC_CAPTURE] ?: false }

    override val pairedDeviceAddress: Flow<String?> = store.data.map { it[PAIRED_DEVICE_ADDRESS] }

    override val lastReplayMigrationContractVersion: Flow<ContractVersion?> = store.data.map { prefs ->
        // Unreadable collapses to null same as Absent, not a distinct branch:
        // a corrupted marker just means "unconfirmed" here, and re-running an
        // already-satisfied replay pass is a safe no-op (every row it would
        // find is already ineligible), unlike contractVersion's Unreadable
        // case, where silently reverting scope is the actual defect.
        val stored = readStoredEnum(prefs[LAST_REPLAY_MIGRATION_CONTRACT_VERSION], ContractVersion.entries)
        (stored as? StoredEnum.Parsed)?.value
    }

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

    override suspend fun saveAutomaticCaptureEnabled(enabled: Boolean) {
        store.edit { it[AUTOMATIC_CAPTURE] = enabled }
    }

    override suspend fun savePairedDeviceAddress(address: String?) {
        store.edit { prefs ->
            if (address == null) prefs.remove(PAIRED_DEVICE_ADDRESS) else prefs[PAIRED_DEVICE_ADDRESS] = address
        }
    }

    override suspend fun saveLastReplayMigrationContractVersion(version: ContractVersion) {
        store.edit { it[LAST_REPLAY_MIGRATION_CONTRACT_VERSION] = version.name }
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val DISPLAY_UNIT = stringPreferencesKey("display_unit")
        val CONTRACT_VERSION = stringPreferencesKey("contract_version")
        val ALWAYS_ON_BRIDGING = booleanPreferencesKey("always_on_bridging")
        val AUTOMATIC_CAPTURE = booleanPreferencesKey("automatic_capture_enabled")
        val PAIRED_DEVICE_ADDRESS = stringPreferencesKey("paired_device_address")
        val LAST_REPLAY_MIGRATION_CONTRACT_VERSION = stringPreferencesKey("last_replay_migration_contract_version")
    }
}
