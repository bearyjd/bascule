@file:Suppress("MagicNumber")

package com.ventouxlabs.bascule.data

import android.content.Context
import android.content.SharedPreferences
import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.network.encryptedPreferences
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
}

interface ScaleProfileStore : ConsentStore {
    val profiles: StateFlow<List<ScaleProfile>>
    val activeProfile: StateFlow<ScaleProfile?>
    fun credentialFor(deviceAddress: String, scaleIndex: Int): ScaleCredential?
    fun saveProfile(profile: ScaleProfile)
    fun deleteProfile(profileId: String)
    fun setActive(profileId: String)
    fun replaceAll(profiles: List<ScaleProfile>)
}

/** Encrypted registry. The JSON blob never leaves encrypted preferences. */
class EncryptedScaleProfileStore(
    context: Context,
    private val legacy: ConsentStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ScaleProfileStore {
    private val prefs: SharedPreferences = encryptedPreferences(context, FILE_NAME)
    private val mutableProfiles = MutableStateFlow(readProfiles())
    override val profiles: StateFlow<List<ScaleProfile>> = mutableProfiles
    private val mutableActive = MutableStateFlow(mutableProfiles.value.firstOrNull { it.active })
    override val activeProfile: StateFlow<ScaleProfile?> = mutableActive

    override fun credentialFor(deviceAddress: String): ScaleCredential? {
        mutableProfiles.value.firstOrNull { it.deviceAddress.equals(deviceAddress, true) && it.active }
            ?.let { return it.credential }
        val migrated = legacy?.credentialFor(deviceAddress) ?: return null
        saveProfile(
            ScaleProfile(
                id = UUID.randomUUID().toString(),
                deviceAddress = deviceAddress.uppercase(),
                scaleIndex = migrated.scaleIndex,
                consentCode = migrated.consentCode,
                label = "Profile ${migrated.scaleIndex}",
                registeredAtMillis = clock(),
                active = true,
            ),
        )
        return migrated
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
        ?: java.security.SecureRandom().nextInt(0xFFFF) + 1

    override fun saveProfile(profile: ScaleProfile) {
        require(profile.scaleIndex in 0..255 && profile.consentCode in 0..0xFFFF)
        persist(ScaleProfileCodec.upsertEnforcingSingleActive(mutableProfiles.value, profile))
    }

    override fun deleteProfile(profileId: String) = persist(mutableProfiles.value.filterNot { it.id == profileId })

    override fun setActive(profileId: String) {
        require(mutableProfiles.value.any { it.id == profileId })
        persist(mutableProfiles.value.map { it.copy(active = it.id == profileId) })
    }

    override fun replaceAll(profiles: List<ScaleProfile>) {
        require(profiles.count { it.active } <= 1)
        persist(profiles.distinctBy { it.id })
    }

    private fun persist(next: List<ScaleProfile>) {
        prefs.edit().putString(KEY_PROFILES, ScaleProfileCodec.encodeToString(next)).commit()
        mutableProfiles.value = next
        mutableActive.value = next.firstOrNull { it.active }
    }

    private fun readProfiles(): List<ScaleProfile> = runCatching {
        prefs.getString(KEY_PROFILES, null)?.let(ScaleProfileCodec::decodeFromString).orEmpty()
    }.getOrDefault(emptyList())

    override fun toString(): String = "EncryptedScaleProfileStore"

    private companion object { const val FILE_NAME = "bascule_scale_profiles"; const val KEY_PROFILES = "profiles_v2" }
}
