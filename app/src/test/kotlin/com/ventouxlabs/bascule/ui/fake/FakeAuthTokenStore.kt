package com.ventouxlabs.bascule.ui.fake

import com.ventouxlabs.bascule.network.AuthTokenStore

/** In-memory [AuthTokenStore] for JVM tests — no EncryptedSharedPreferences/keystore needed. */
class FakeAuthTokenStore(initialToken: String? = null) : AuthTokenStore {

    private var stored: String? = initialToken

    override fun isSet(): Boolean = !stored.isNullOrEmpty()

    override fun token(): String? = stored

    override fun save(token: String) {
        stored = token
    }

    override fun clear() {
        stored = null
    }
}
