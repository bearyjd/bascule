package com.ventouxlabs.bascule.ui.fake

import com.ventouxlabs.bascule.network.SessionCookieStore

/** In-memory [SessionCookieStore] for JVM tests — no EncryptedSharedPreferences/keystore needed. */
class FakeSessionCookieStore(initialCookie: String? = null) : SessionCookieStore {

    private var stored: String? = initialCookie

    override fun isSet(): Boolean = !stored.isNullOrEmpty()

    override fun cookie(): String? = stored

    override fun save(cookie: String) {
        stored = cookie
    }

    override fun clear() {
        stored = null
    }
}
