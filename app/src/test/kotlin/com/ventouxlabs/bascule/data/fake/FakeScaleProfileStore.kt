package com.ventouxlabs.bascule.data.fake

import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.data.ScaleProfile
import com.ventouxlabs.bascule.data.ScaleProfileCodec
import com.ventouxlabs.bascule.data.ScaleProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [ScaleProfileStore] for JVM tests — no EncryptedSharedPreferences/Keystore needed. */
class FakeScaleProfileStore(initial: List<ScaleProfile> = emptyList()) : ScaleProfileStore {

    private val mutableProfiles = MutableStateFlow(initial)
    override val profiles: StateFlow<List<ScaleProfile>> = mutableProfiles
    private val mutableActive = MutableStateFlow(initial.firstOrNull { it.active })
    override val activeProfile: StateFlow<ScaleProfile?> = mutableActive

    override fun credentialFor(deviceAddress: String): ScaleCredential? =
        mutableProfiles.value.firstOrNull { it.deviceAddress.equals(deviceAddress, true) && it.active }?.credential

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
                id = "${deviceAddress}-${credential.scaleIndex}",
                deviceAddress = deviceAddress,
                scaleIndex = credential.scaleIndex,
                consentCode = credential.consentCode,
                label = "Profile ${credential.scaleIndex}",
                registeredAtMillis = 0L,
                active = mutableProfiles.value.none { it.active },
            ),
        )
    }

    override fun clear(deviceAddress: String) {
        persist(mutableProfiles.value.filterNot { it.deviceAddress.equals(deviceAddress, true) })
    }

    override fun newConsentCode(): Int = FIXED_CONSENT_CODE

    override fun saveProfile(profile: ScaleProfile) {
        persist(ScaleProfileCodec.upsertEnforcingSingleActive(mutableProfiles.value, profile))
    }

    override fun deleteProfile(profileId: String) {
        persist(mutableProfiles.value.filterNot { it.id == profileId })
    }

    override fun setActive(profileId: String) {
        persist(mutableProfiles.value.map { it.copy(active = it.id == profileId) })
    }

    override fun replaceAll(profiles: List<ScaleProfile>) {
        persist(profiles.distinctBy { it.id })
    }

    private fun persist(next: List<ScaleProfile>) {
        mutableProfiles.value = next
        mutableActive.value = next.firstOrNull { it.active }
    }

    private companion object {
        const val FIXED_CONSENT_CODE = 0x1234
    }
}
