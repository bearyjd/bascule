package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.network.ContractVersion
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class BackupCredentialType { NONE, TOKEN, SESSION }

data class PortableSettings(
    val baseUrl: String,
    val displayUnit: WeightUnit,
    val contractVersion: ContractVersion,
    val alwaysOnBridging: Boolean,
    val credentialType: BackupCredentialType,
    val credentialValue: String?,
    val pairedDeviceAddress: String?,
    val scaleCredential: ScaleCredential?,
    val profiles: List<ScaleProfile> = emptyList(),
    val automaticCaptureEnabled: Boolean = false,
    /**
     * Whether the backup's format carries a profile registry at all. Distinct
     * from [profiles] being empty: a pre-registry backup says nothing about the
     * device's profiles, whereas a registry-era one that carried none is the
     * only case where "the user had no profiles" is actually attested. Importing
     * must not delete registrations on the strength of a silence it misread.
     */
    val supportsProfiles: Boolean = false,
)

/** Passphrase-encrypted, versioned settings file. No secret is ever emitted as plaintext. */
object SettingsBackupCodec {

    fun encrypt(settings: PortableSettings, passphrase: String): ByteArray {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) { "Passphrase must be at least 8 characters" }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
        val plaintext = encode(settings).toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, iv).doFinal(plaintext)
        return ByteBuffer.allocate(MAGIC.size + salt.size + iv.size + ciphertext.size)
            .put(MAGIC)
            .put(salt)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    fun decrypt(bytes: ByteArray, passphrase: String): PortableSettings {
        require(bytes.size <= MAX_BACKUP_BYTES) { "Settings backup is too large" }
        require(bytes.size > MAGIC.size + SALT_BYTES + IV_BYTES) { "Not a Bascule settings backup" }
        val buffer = ByteBuffer.wrap(bytes)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Not a Bascule settings backup" }
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val plaintext = cipher(Cipher.DECRYPT_MODE, passphrase, salt, iv).doFinal(ciphertext)
        return decode(String(plaintext, StandardCharsets.UTF_8))
    }

    private fun cipher(mode: Int, passphrase: String, salt: ByteArray, iv: ByteArray): Cipher {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(encoded, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(MAGIC)
        }
    }

    private fun encode(settings: PortableSettings): String = buildJsonObject {
        put("version", JsonPrimitive(FORMAT_VERSION))
        put("base_url", JsonPrimitive(settings.baseUrl))
        put("display_unit", JsonPrimitive(settings.displayUnit.name))
        put("contract_version", JsonPrimitive(settings.contractVersion.name))
        put("always_on_bridging", JsonPrimitive(settings.alwaysOnBridging))
        put("credential_type", JsonPrimitive(settings.credentialType.name))
        put("credential_value", settings.credentialValue?.let(::JsonPrimitive) ?: JsonNull)
        put("paired_device_address", settings.pairedDeviceAddress?.let(::JsonPrimitive) ?: JsonNull)
        put("scale_index", settings.scaleCredential?.scaleIndex?.let(::JsonPrimitive) ?: JsonNull)
        put("consent_code", settings.scaleCredential?.consentCode?.let(::JsonPrimitive) ?: JsonNull)
        put("automatic_capture_enabled", JsonPrimitive(settings.automaticCaptureEnabled))
        put("profiles", ScaleProfileCodec.encode(settings.profiles))
    }.toString()

    /**
     * `internal` rather than private so the malformed-input cases can be tested
     * directly: [encrypt] only accepts a well-formed [PortableSettings], so a
     * corrupt or newer-version payload cannot be produced through it.
     */
    internal fun decode(text: String): PortableSettings {
        val obj = Json.parseToJsonElement(text).jsonObject
        val version = obj.getValue("version").jsonPrimitive.int
        require(version in 1..FORMAT_VERSION) { "Unsupported backup version" }
        val address = obj["paired_device_address"]?.jsonPrimitive?.contentOrNull
        val scaleIndex = obj["scale_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val consentCode = obj["consent_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val credentialType = requireKnownEnum<BackupCredentialType>(obj.getValue("credential_type"), "credential_type")
        val credentialValue = obj["credential_value"]?.jsonPrimitive?.contentOrNull
        require(credentialType == BackupCredentialType.NONE || !credentialValue.isNullOrEmpty()) {
            "Backup credential is missing"
        }
        require((scaleIndex == null) == (consentCode == null)) {
            "Backup scale mapping is incomplete"
        }
        val supportsProfiles = version >= PROFILE_REGISTRY_VERSION
        val profilesElement = obj["profiles"]?.takeUnless { it is JsonNull }
        // A safe cast here would turn a corrupt `profiles` field into an empty
        // list, and an empty list is what makes the import path delete the
        // user's registration. Fail the import instead.
        require(!supportsProfiles || profilesElement == null || profilesElement is JsonArray) {
            "Backup profile list is malformed"
        }
        val profiles = if (supportsProfiles && profilesElement is JsonArray) {
            ScaleProfileCodec.decode(profilesElement)
        } else {
            emptyList()
        }
        require(profiles.count { it.active } <= 1) { "Backup has more than one active profile" }
        return PortableSettings(
            baseUrl = obj.getValue("base_url").jsonPrimitive.content,
            displayUnit = knownEnumOrDefault(obj["display_unit"], WeightUnit.KILOGRAMS),
            contractVersion = knownEnumOrDefault(obj["contract_version"], ContractVersion.V1_WEIGHT_ONLY),
            alwaysOnBridging = obj.getValue("always_on_bridging").jsonPrimitive.boolean,
            credentialType = credentialType,
            credentialValue = credentialValue,
            pairedDeviceAddress = address,
            scaleCredential = if (address != null && scaleIndex != null && consentCode != null) {
                require(
                    scaleIndex in SigWeightProfile.SCALE_INDEX_RANGE &&
                        consentCode in SigWeightProfile.CONSENT_CODE_RANGE,
                ) {
                    "Invalid scale credential"
                }
                ScaleCredential(scaleIndex, consentCode)
            } else {
                null
            },
            profiles = profiles,
            automaticCaptureEnabled = obj["automatic_capture_enabled"]?.jsonPrimitive?.boolean ?: false,
            supportsProfiles = supportsProfiles,
        )
    }

    /**
     * A name the running binary does not know means the backup was written by a
     * newer build — [FORMAT_VERSION] does not catch this, because adding an enum
     * constant is a source change that does not feel like a format change. For a
     * credential type there is no safe default: falling back to `NONE` would
     * silently drop the credential the backup exists to carry.
     */
    private inline fun <reified T : Enum<T>> requireKnownEnum(element: JsonElement, field: String): T {
        val name = element.jsonPrimitive.content
        return runCatching { enumValueOf<T>(name) }.getOrNull()
            ?: throw IllegalArgumentException("Backup $field \"$name\" is not recognised by this app version")
    }

    /**
     * The display unit and contract version are user-repickable settings, so an
     * unknown name degrades to the default rather than failing the whole import
     * — the same tolerance [DataStoreConfigStore] applies to its own stored values.
     */
    private inline fun <reified T : Enum<T>> knownEnumOrDefault(element: JsonElement?, default: T): T =
        (element as? JsonPrimitive)?.contentOrNull?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private val JsonPrimitive.contentOrNull: String?
        get() = if (this is JsonNull) null else content

    private const val FORMAT_VERSION = 2

    /** The first format version whose files carry a `profiles` array. */
    private const val PROFILE_REGISTRY_VERSION = 2
    const val MIN_PASSPHRASE_LENGTH = 8
    const val MAX_BACKUP_BYTES = 1024 * 1024
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 210_000
    private val MAGIC = "BASCULE1".toByteArray(StandardCharsets.US_ASCII)
    private val secureRandom = SecureRandom()
}
