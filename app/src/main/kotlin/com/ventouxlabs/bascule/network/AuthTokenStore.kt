package com.ventouxlabs.bascule.network

import android.content.Context
import android.content.SharedPreferences

/**
 * The VitalForge bearer token. EncryptedSharedPreferences only — never plain
 * preferences, never source, never a log line (agent prompt ground rules,
 * 00-design.md §8.8).
 *
 * The value is never returned for display; the UI asks [isSet].
 *
 * An interface, mirroring [com.ventouxlabs.bascule.ble.session.ConsentStore],
 * so ViewModels can be unit-tested against a fake rather than needing a real
 * `Context`/EncryptedSharedPreferences.
 */
interface AuthTokenStore {
    fun isSet(): Boolean

    /** Read at request-build time only, so the token is not held in a field. */
    fun token(): String?

    fun save(token: String)
    fun clear()
}

class EncryptedAuthTokenStore(context: Context) : AuthTokenStore {

    private val prefs: SharedPreferences = encryptedPreferences(context, FILE_NAME)

    override fun isSet(): Boolean = !prefs.getString(KEY_TOKEN, null).isNullOrEmpty()

    override fun token(): String? = prefs.getString(KEY_TOKEN, null)

    override fun save(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    override fun toString(): String = "EncryptedAuthTokenStore(isSet=${isSet()})"

    private companion object {
        const val FILE_NAME = "bascule_auth"
        const val KEY_TOKEN = "vitalforge_token"
    }
}
