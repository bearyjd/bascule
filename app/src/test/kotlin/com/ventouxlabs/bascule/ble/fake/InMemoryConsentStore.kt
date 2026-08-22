package com.ventouxlabs.bascule.ble.fake

import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential

/**
 * In-memory [ConsentStore] for JVM tests. The production store is backed by
 * EncryptedSharedPreferences, which needs a real Android keystore and is
 * therefore covered by an instrumented test instead — a keystore failure in a
 * contract test would be red for the wrong reason.
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
