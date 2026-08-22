package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.decoders.HandshakeContext
import com.ventouxlabs.bascule.ble.decoders.ScaleDecoder

/**
 * The BLE state machine of 00-design.md §2.1: connect, discover, run the
 * ADR-007 handshake, subscribe, consume indications, persist at EMITTED, tear
 * down exactly once.
 *
 * Pure control — it holds no protocol knowledge. It executes the [GattOp]s a
 * [ScaleDecoder] asks for against a [GattTransport] and feeds inbound bytes
 * back in, which is what makes every failure edge reproducible in a JVM test
 * against a fake transport.
 *
 * PHASE 2 SKELETON. The run loop is Phase 3 work packages WP-06 (connect,
 * discover, teardown), WP-07 (handshake gating) and WP-10 (measurement). Until
 * those land [run] reports that the session produced nothing, which is what the
 * session-level contract tests assert against — see docs/prp/02-ci-notes.md.
 */
class GattSession(
    private val transport: GattTransport,
    val decoder: ScaleDecoder,
    private val consentStore: ConsentStore,
    private val deviceAddress: String,
) {

    /**
     * Assembles the handshake inputs the decoder needs: the credential stored
     * for this scale if there is one, and a fresh consent code to register with
     * if there is not.
     */
    fun handshakeContext(): HandshakeContext = HandshakeContext(
        storedCredential = consentStore.credentialFor(deviceAddress),
        freshConsentCode = consentStore.newConsentCode(),
    )

    /**
     * Persists the mapping the scale just assigned, so the next session sends
     * Consent directly instead of registering a second user slot (ADR-007).
     */
    fun rememberCredential(credential: ScaleCredential) {
        consentStore.save(deviceAddress, credential)
    }

    suspend fun run(): SessionOutcome {
        transport.close()
        return SessionOutcome.Missed(MissReason.NO_MEASUREMENT)
    }
}
