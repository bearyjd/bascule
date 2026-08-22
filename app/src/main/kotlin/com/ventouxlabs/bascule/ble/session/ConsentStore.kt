package com.ventouxlabs.bascule.ble.session

/**
 * The scale-side half of the ADR-007 handshake: an index the scale assigned and
 * the 16-bit consent code that unlocks it.
 *
 * This is credential material in the same sense the VitalForge token is —
 * whoever holds it can read the household's body-composition history off the
 * scale — so it is only ever persisted through an encrypted [ConsentStore].
 */
data class ScaleCredential(val scaleIndex: Int, val consentCode: Int)

/**
 * Persisted `scaleIndex -> consentCode` mapping, keyed by device address, so a
 * returning session sends Consent directly instead of registering a new user on
 * every weigh-in (ADR-007).
 */
interface ConsentStore {
    fun credentialFor(deviceAddress: String): ScaleCredential?
    fun save(deviceAddress: String, credential: ScaleCredential)
    fun clear(deviceAddress: String)

    /** A fresh 16-bit consent code for a Register New User attempt. */
    fun newConsentCode(): Int
}
