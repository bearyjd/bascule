package com.ventouxlabs.bascule.data

import android.content.Context
import android.content.SharedPreferences
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.network.encryptedPreferences
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ScaleProfile(
    val id: String,
    val deviceAddress: String,
    val scaleIndex: Int,
    val consentCode: Int,
    val label: String,
    val registeredAtMillis: Long,
    val active: Boolean,
    val lastVerifiedAtMillis: Long? = null,
    val initializationIncomplete: Boolean = false,
) {
    val credential: ScaleCredential get() = ScaleCredential(scaleIndex, consentCode)

    /**
     * The generated `toString()` would render `consentCode`, and `ScaleUiState`
     * holds a `List<ScaleProfile>`, so its own generated one would print every
     * stored code transitively. Same rule the encrypted stores apply to
     * themselves — consent codes are never a log line (see [ScaleCredential]).
     */
    override fun toString(): String = "ScaleProfile(id=$id, address=$deviceAddress, index=$scaleIndex)"
}

interface ScaleProfileStore : ConsentStore {
    val profiles: StateFlow<List<ScaleProfile>>
    val activeProfile: StateFlow<ScaleProfile?>

    /**
     * Non-null once, at construction, when a stored registry blob existed but
     * did not decode — the blob itself is quarantined rather than lost, but the
     * user's registrations still read as empty until they re-link or
     * re-register. Fixed after construction, not a [kotlinx.coroutines.flow.Flow]:
     * the read happens once, before anything can observe this store.
     */
    val readFailure: Throwable?
    fun credentialFor(deviceAddress: String, scaleIndex: Int): ScaleCredential?
    fun saveProfile(profile: ScaleProfile)
    fun deleteProfile(profileId: String)
    fun setActive(profileId: String)
    fun replaceAll(profiles: List<ScaleProfile>)

    /**
     * Promotes a pre-registry credential for [deviceAddress] into the profile
     * registry, if one exists there and no active profile already covers it.
     * Split out of [credentialFor] so that reading a credential never writes:
     * the one caller that wants the migration asks for it by name.
     */
    fun migrateLegacyCredential(deviceAddress: String)
}

/** Encrypted registry. The JSON blob never leaves encrypted preferences. */
class EncryptedScaleProfileStore(
    context: Context,
    private val legacy: ConsentStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ScaleProfileStore {
    private val prefs: SharedPreferences = encryptedPreferences(context, FILE_NAME)

    /**
     * Non-null when a stored blob existed but did not decode. The blob itself
     * has been copied to [KEY_QUARANTINE] by then, so the subsequent write that
     * overwrites [KEY_PROFILES] loses nothing recoverable.
     */
    override val readFailure: Throwable?
    private val mutableProfiles: MutableStateFlow<List<ScaleProfile>>

    init {
        val (initial, failure) = loadOrQuarantine()
        mutableProfiles = MutableStateFlow(initial)
        readFailure = failure
    }

    override val profiles: StateFlow<List<ScaleProfile>> = mutableProfiles
    private val mutableActive = MutableStateFlow(mutableProfiles.value.firstOrNull { it.active })
    override val activeProfile: StateFlow<ScaleProfile?> = mutableActive

    /**
     * Pure read: the legacy store is consulted, never written. See [migrateLegacyCredential].
     *
     * Matches on address alone, preferring the active profile but falling back
     * to an inactive one: the handshake path asks "do we hold *any* credential
     * for this device", and answering null for a registered-but-inactive
     * profile makes it register again, consuming another of the scale's eight
     * slots for a device that already occupies one.
     */
    override fun credentialFor(deviceAddress: String): ScaleCredential? {
        val matches = mutableProfiles.value.filter { it.deviceAddress.equals(deviceAddress, true) }
        return (matches.firstOrNull { it.active } ?: matches.firstOrNull())?.credential
            ?: legacy?.credentialFor(deviceAddress)
    }

    override fun migrateLegacyCredential(deviceAddress: String) {
        ScaleProfileCodec.legacyMigrationProfile(
            current = mutableProfiles.value,
            deviceAddress = deviceAddress,
            legacy = legacy?.credentialFor(deviceAddress),
            id = UUID.randomUUID().toString(),
            nowMillis = clock(),
        )?.let {
            saveProfile(it)
            // Only after the write lands — persist() commits synchronously, so
            // by here the registry copy is durable. Clearing first would be the
            // ordering that loses an unrecoverable consent code on a crash, and
            // clearing when the migration returned null would drop a legacy
            // entry that the existing active profile does not cover.
            legacy?.clear(deviceAddress)
        }
    }

    override fun credentialFor(deviceAddress: String, scaleIndex: Int): ScaleCredential? =
        mutableProfiles.value.firstOrNull {
            it.deviceAddress.equals(deviceAddress, true) && it.scaleIndex == scaleIndex
        }?.credential

    override fun save(deviceAddress: String, credential: ScaleCredential) {
        val existing = mutableProfiles.value.firstOrNull {
            it.deviceAddress.equals(deviceAddress, true) && it.scaleIndex == credential.scaleIndex
        }
        saveProfile(
            existing?.copy(consentCode = credential.consentCode) ?: ScaleProfile(
                id = UUID.randomUUID().toString(),
                deviceAddress = deviceAddress.uppercase(),
                scaleIndex = credential.scaleIndex,
                consentCode = credential.consentCode,
                label = "Profile ${credential.scaleIndex}",
                registeredAtMillis = clock(),
                active = mutableProfiles.value.none { it.active },
            ),
        )
    }

    override fun clear(deviceAddress: String) {
        replaceAll(mutableProfiles.value.filterNot { it.deviceAddress.equals(deviceAddress, true) })
        legacy?.clear(deviceAddress)
    }

    override fun newConsentCode(): Int = legacy?.newConsentCode()
        ?: secureRandom.nextInt(SigWeightProfile.CONSENT_CODE_MAX) + 1

    override fun saveProfile(profile: ScaleProfile) {
        ScaleProfileCodec.requireWithinBounds(profile)
        persist(ScaleProfileCodec.upsertEnforcingSingleActive(mutableProfiles.value, profile))
    }

    override fun deleteProfile(profileId: String) = persist(mutableProfiles.value.filterNot { it.id == profileId })

    override fun setActive(profileId: String) {
        require(mutableProfiles.value.any { it.id == profileId }) { "No profile with id $profileId" }
        persist(mutableProfiles.value.map { it.copy(active = it.id == profileId) })
    }

    override fun replaceAll(profiles: List<ScaleProfile>) {
        require(profiles.count { it.active } <= 1) { "At most one profile may be active" }
        // The gate saveProfile enforces, applied to the path an imported backup
        // takes. Deliberately not in persist(): deleteProfile routes through
        // there too, and must stay able to remove an already-stored bad row.
        profiles.forEach(ScaleProfileCodec::requireWithinBounds)
        persist(profiles.distinctBy { it.id })
    }

    private fun persist(next: List<ScaleProfile>) {
        prefs.edit().putString(KEY_PROFILES, ScaleProfileCodec.encodeToString(next)).commit()
        mutableProfiles.value = next
        mutableActive.value = next.firstOrNull { it.active }
    }

    /**
     * Reads the registry, copying the raw blob aside first if it did not decode.
     * The quarantine write happens here, before the store is usable, so it is
     * ordered ahead of every [persist] that could overwrite the original —
     * `commit()` rather than `apply()` for the same reason.
     */
    private fun loadOrQuarantine(): Pair<List<ScaleProfile>, Throwable?> =
        when (val stored = ScaleProfileCodec.readStored(prefs.getString(KEY_PROFILES, null))) {
            ScaleProfileCodec.StoredProfiles.Absent -> emptyList<ScaleProfile>() to null
            is ScaleProfileCodec.StoredProfiles.Parsed -> stored.profiles to null
            is ScaleProfileCodec.StoredProfiles.Unreadable -> {
                prefs.edit().putString(KEY_QUARANTINE, stored.raw).commit()
                emptyList<ScaleProfile>() to stored.cause
            }
        }

    override fun toString(): String = "EncryptedScaleProfileStore"

    private companion object {
        const val FILE_NAME = "bascule_scale_profiles"
        const val KEY_PROFILES = "profiles_v2"

        /** Holds the last blob that failed to decode, so a later build can recover it. */
        const val KEY_QUARANTINE = "profiles_v2_unreadable"
        val secureRandom = SecureRandom()
    }
}
