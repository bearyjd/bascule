package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure list-level operations on [ScaleProfile]: the JSON encoding shared by
 * [EncryptedScaleProfileStore]'s registry and [SettingsBackupCodec]'s
 * portable export, and the single-active-profile upsert invariant every
 * write path in this codebase relies on. Kept free of encryption/Android
 * dependencies so it is unit-testable without a Keystore — the encrypted
 * stores that wrap it are covered by instrumented tests instead (same
 * split as [com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore]'s own
 * KDoc explains for [com.ventouxlabs.bascule.ble.session.ConsentStore]).
 */
object ScaleProfileCodec {
    fun encode(items: List<ScaleProfile>): JsonArray = buildJsonArray {
        items.forEach { profile -> add(encodeOne(profile)) }
    }

    fun encodeToString(items: List<ScaleProfile>): String = encode(items).toString()

    fun decode(array: JsonArray): List<ScaleProfile> = array.map { decodeOne(it as JsonObject) }

    fun decodeFromString(text: String): List<ScaleProfile> = decode(Json.parseToJsonElement(text).jsonArray)

    /**
     * The three states a persisted registry blob can be in. [Unreadable] exists
     * because the other two are not exhaustive and conflating them destroys
     * data: a store that answers "no profiles" for a blob it merely failed to
     * parse overwrites that blob on its next write, and consent codes are not
     * recoverable from inside the app — re-establishing one means physically
     * re-registering with the scale and burning one of its eight slots.
     */
    sealed interface StoredProfiles {
        /** Nothing has ever been written. Defaulting to an empty registry is safe. */
        data object Absent : StoredProfiles

        data class Parsed(val profiles: List<ScaleProfile>) : StoredProfiles

        /** A blob exists and did not decode. [raw] is retained so it can be quarantined, never dropped. */
        data class Unreadable(val raw: String, val cause: Throwable) : StoredProfiles
    }

    /**
     * Classifies a stored blob without ever throwing. Every shape failure
     * [decodeOne] can raise — a missing key, a wrong JSON type, an unparseable
     * number, an address or index the current build's validation rejects —
     * collapses here into [StoredProfiles.Unreadable] rather than into an
     * empty list. Pure, so the distinction is unit-testable without a Keystore.
     */
    fun readStored(blob: String?): StoredProfiles {
        if (blob == null) return StoredProfiles.Absent
        return runCatching { StoredProfiles.Parsed(decodeFromString(blob)) }
            .getOrElse { StoredProfiles.Unreadable(blob, it) }
    }

    /**
     * The profile a legacy-store migration should persist for [deviceAddress],
     * or null when there is nothing to migrate — either the registry already
     * has an active profile for that address, or the legacy store has no
     * credential. Pure, so the migration rule is testable without a Keystore.
     */
    fun legacyMigrationProfile(
        current: List<ScaleProfile>,
        deviceAddress: String,
        legacy: ScaleCredential?,
        id: String,
        nowMillis: Long,
    ): ScaleProfile? {
        if (current.any { it.deviceAddress.equals(deviceAddress, true) && it.active }) return null
        val credential = legacy ?: return null
        return ScaleProfile(
            id = id,
            deviceAddress = deviceAddress.uppercase(),
            scaleIndex = credential.scaleIndex,
            consentCode = credential.consentCode,
            label = "Profile ${credential.scaleIndex}",
            registeredAtMillis = nowMillis,
            active = true,
        )
    }

    /**
     * The bounds every persisted profile must satisfy. Called by each store's
     * write paths so an out-of-range index can never reach the BLE scan filter,
     * regardless of which entry point produced the profile.
     */
    fun requireWithinBounds(profile: ScaleProfile) {
        require(profile.scaleIndex in SigWeightProfile.SCALE_INDEX_RANGE) {
            "Scale index ${profile.scaleIndex} out of range"
        }
        require(profile.consentCode in SigWeightProfile.CONSENT_CODE_RANGE) {
            "Consent code out of range"
        }
    }

    /** Upserts [profile] by id; if it is active, every other profile is deactivated first. */
    fun upsertEnforcingSingleActive(current: List<ScaleProfile>, profile: ScaleProfile): List<ScaleProfile> {
        val next = current.filterNot { it.id == profile.id }.toMutableList()
        if (profile.active) {
            for (index in next.indices) next[index] = next[index].copy(active = false)
        }
        next += profile
        return next
    }

    private fun encodeOne(profile: ScaleProfile) = buildJsonObject {
        put("id", JsonPrimitive(profile.id))
        put("address", JsonPrimitive(profile.deviceAddress))
        put("index", JsonPrimitive(profile.scaleIndex))
        put("code", JsonPrimitive(profile.consentCode))
        put("label", JsonPrimitive(profile.label))
        put("registered", JsonPrimitive(profile.registeredAtMillis))
        put("active", JsonPrimitive(profile.active))
        profile.lastVerifiedAtMillis?.let { put("verified", JsonPrimitive(it)) }
        put("incomplete", JsonPrimitive(profile.initializationIncomplete))
    }

    /**
     * A decoded profile reaches [EncryptedScaleProfileStore.replaceAll] from an
     * imported backup file, so this is a trust boundary: validate here with the
     * same bounds the hand-typed path enforces, or a malformed `address` reaches
     * `ScanFilter.setDeviceAddress` and throws out of a background coroutine at
     * launch, on every launch.
     */
    private fun decodeOne(obj: JsonObject): ScaleProfile {
        val deviceAddress = obj.getValue("address").jsonPrimitive.content.uppercase()
        val scaleIndex = obj.getValue("index").jsonPrimitive.int
        val consentCode = obj.getValue("code").jsonPrimitive.int
        require(BLUETOOTH_ADDRESS.matches(deviceAddress)) { "Profile has an invalid Bluetooth address" }
        require(scaleIndex in SigWeightProfile.SCALE_INDEX_RANGE) { "Profile scale index out of range" }
        require(consentCode in SigWeightProfile.CONSENT_CODE_RANGE) { "Profile consent code out of range" }
        return ScaleProfile(
            id = obj.getValue("id").jsonPrimitive.content,
            deviceAddress = deviceAddress,
            scaleIndex = scaleIndex,
            consentCode = consentCode,
            label = obj.getValue("label").jsonPrimitive.content,
            registeredAtMillis = obj.getValue("registered").jsonPrimitive.content.toLong(),
            active = obj.getValue("active").jsonPrimitive.boolean,
            lastVerifiedAtMillis = obj["verified"]?.jsonPrimitive?.content?.toLongOrNull(),
            initializationIncomplete = obj["incomplete"]?.jsonPrimitive?.boolean ?: false,
        )
    }

    /** The canonical Bluetooth MAC form, shared with the hand-entry path in `ConfigViewModel`. */
    val BLUETOOTH_ADDRESS = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
}
