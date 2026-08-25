package com.ventouxlabs.bascule.data

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

    private fun decodeOne(obj: JsonObject) = ScaleProfile(
        id = obj.getValue("id").jsonPrimitive.content,
        deviceAddress = obj.getValue("address").jsonPrimitive.content,
        scaleIndex = obj.getValue("index").jsonPrimitive.int,
        consentCode = obj.getValue("code").jsonPrimitive.int,
        label = obj.getValue("label").jsonPrimitive.content,
        registeredAtMillis = obj.getValue("registered").jsonPrimitive.content.toLong(),
        active = obj.getValue("active").jsonPrimitive.boolean,
        lastVerifiedAtMillis = obj["verified"]?.jsonPrimitive?.content?.toLongOrNull(),
        initializationIncomplete = obj["incomplete"]?.jsonPrimitive?.boolean ?: false,
    )
}
