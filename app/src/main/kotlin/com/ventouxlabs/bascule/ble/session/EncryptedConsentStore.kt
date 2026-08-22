package com.ventouxlabs.bascule.ble.session

import android.content.Context
import android.content.SharedPreferences
import com.ventouxlabs.bascule.network.encryptedPreferences
import java.security.SecureRandom

/**
 * [ConsentStore] backed by EncryptedSharedPreferences.
 *
 * A consent code is a shared secret with the scale in the same sense the
 * VitalForge token is a shared secret with the server (ADR-007), so it falls
 * under the same ground rule: encrypted storage only, never plain preferences
 * and never a log line.
 */
class EncryptedConsentStore(context: Context) : ConsentStore {

    private val prefs: SharedPreferences = encryptedPreferences(context, FILE_NAME)
    private val random = SecureRandom()

    override fun credentialFor(deviceAddress: String): ScaleCredential? {
        val index = prefs.getInt(indexKey(deviceAddress), NOT_SET)
        val code = prefs.getInt(codeKey(deviceAddress), NOT_SET)
        if (index == NOT_SET || code == NOT_SET) return null
        return ScaleCredential(index, code)
    }

    override fun save(deviceAddress: String, credential: ScaleCredential) {
        prefs.edit()
            .putInt(indexKey(deviceAddress), credential.scaleIndex)
            .putInt(codeKey(deviceAddress), credential.consentCode)
            .apply()
    }

    override fun clear(deviceAddress: String) {
        prefs.edit()
            .remove(indexKey(deviceAddress))
            .remove(codeKey(deviceAddress))
            .apply()
    }

    /** 16-bit, non-zero, from a cryptographic RNG. */
    override fun newConsentCode(): Int = random.nextInt(CODE_RANGE) + 1

    override fun toString(): String = "EncryptedConsentStore"

    private fun indexKey(address: String) = "$address.index"
    private fun codeKey(address: String) = "$address.code"

    private companion object {
        const val FILE_NAME = "bascule_scale_consent"
        const val NOT_SET = -1
        const val CODE_RANGE = 0xFFFF
    }
}
