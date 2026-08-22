package com.ventouxlabs.bascule.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The VitalForge bearer token. EncryptedSharedPreferences only — never plain
 * preferences, never source, never a log line (agent prompt ground rules,
 * 00-design.md §8.8).
 *
 * The value is never returned for display; the UI asks [isSet].
 */
class AuthTokenStore(context: Context) {

    private val prefs: SharedPreferences = encryptedPreferences(context, FILE_NAME)

    fun isSet(): Boolean = !prefs.getString(KEY_TOKEN, null).isNullOrEmpty()

    /** Read at request-build time only, so the token is not held in a field. */
    fun token(): String? = prefs.getString(KEY_TOKEN, null)

    fun save(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    override fun toString(): String = "AuthTokenStore(isSet=${isSet()})"

    private companion object {
        const val FILE_NAME = "bascule_auth"
        const val KEY_TOKEN = "vitalforge_token"
    }
}

internal fun encryptedPreferences(context: Context, fileName: String): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        fileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
