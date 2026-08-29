package com.ventouxlabs.bascule.ble.fake

import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential

/**
 * In-memory [ConsentStore] for JVM tests. The production store is backed by
 * EncryptedSharedPreferences, which needs a real Android keystore, so a
 * keystore failure in a contract test would be red for the wrong reason —
 * this fake exists to keep contract tests keystore-independent. NOTE: this
 * project has no `app/src/androidTest` tree, so the real EncryptedSharedPreferences
 * path is not covered by any instrumented test either; that is a known gap,
 * not something this fake substitutes for.
 */
class InMemoryConsentStore(
    private val fixedConsentCode: Int = 0x1234,
) : ConsentStore {

    private val credentials = mutableMapOf<String, ScaleCredential>()
    var newConsentCodeCallCount = 0
        private set

    override fun credentialFor(deviceAddress: String): ScaleCredential? = credentials[deviceAddress]

    override fun save(deviceAddress: String, credential: ScaleCredential) {
        credentials[deviceAddress] = credential
    }

    override fun clear(deviceAddress: String) {
        credentials.remove(deviceAddress)
    }

    override fun newConsentCode(): Int {
        newConsentCodeCallCount++
        return fixedConsentCode
    }
}
