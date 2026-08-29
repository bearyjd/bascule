package com.ventouxlabs.bascule.network

import android.content.Context
import android.content.SharedPreferences

/**
 * The VitalForge `vf_session` cookie value — a login-derived credential,
 * independent of and mutually exclusive with [AuthTokenStore]'s bearer
 * token. EncryptedSharedPreferences only, own file, same rules as
 * [AuthTokenStore] (never plain preferences, never source, never a log
 * line).
 */
interface SessionCookieStore {
    fun isSet(): Boolean

    /** Read at request-build time only, so the cookie is not held in a field. */
    fun cookie(): String?

    fun save(cookie: String)
    fun clear()
}

class EncryptedSessionCookieStore(context: Context) : SessionCookieStore {

    private val prefs: SharedPreferences = encryptedPreferences(context, FILE_NAME)

    override fun isSet(): Boolean = !prefs.getString(KEY_COOKIE, null).isNullOrEmpty()

    override fun cookie(): String? = prefs.getString(KEY_COOKIE, null)

    override fun save(cookie: String) {
        prefs.edit().putString(KEY_COOKIE, cookie).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_COOKIE).apply()
    }

    override fun toString(): String = "EncryptedSessionCookieStore(isSet=${isSet()})"

    private companion object {
        const val FILE_NAME = "bascule_session"
        const val KEY_COOKIE = "vitalforge_session_cookie"
    }
}
