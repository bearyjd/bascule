package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.network.ContractVersion
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupCodecTest {

    private val settings = PortableSettings(
        baseUrl = "https://weight.grepon.cc",
        displayUnit = WeightUnit.POUNDS,
        contractVersion = ContractVersion.V1_WEIGHT_ONLY,
        alwaysOnBridging = true,
        credentialType = BackupCredentialType.SESSION,
        credentialValue = "secret-session-cookie",
        pairedDeviceAddress = "E7:DB:51:F1:36:91",
        scaleCredential = ScaleCredential(2, 1234),
        profiles = listOf(
            ScaleProfile(
                id = "bf720-slot-2",
                deviceAddress = "E7:DB:51:F1:36:91",
                scaleIndex = 2,
                consentCode = 1234,
                label = "Profile 2",
                registeredAtMillis = 1_777_777_777L,
                active = true,
                lastVerifiedAtMillis = 1_888_888_888L,
            ),
        ),
        automaticCaptureEnabled = true,
        supportsProfiles = true,
    )

    /** The current format's plaintext, with [overrides] applied to individual fields. */
    private fun payload(vararg overrides: Pair<String, String>): String {
        val fields = linkedMapOf(
            "version" to "2",
            "base_url" to "\"https://weight.grepon.cc\"",
            "display_unit" to "\"POUNDS\"",
            "contract_version" to "\"V1_WEIGHT_ONLY\"",
            "always_on_bridging" to "true",
            "credential_type" to "\"SESSION\"",
            "credential_value" to "\"secret-session-cookie\"",
            "paired_device_address" to "null",
            "scale_index" to "null",
            "consent_code" to "null",
            "automatic_capture_enabled" to "true",
            "profiles" to ScaleProfileCodec.encodeToString(settings.profiles),
        )
        overrides.forEach { (key, value) -> fields[key] = value }
        return fields.entries.joinToString(prefix = "{", postfix = "}") { "\"${it.key}\":${it.value}" }
    }

    @Test
    fun encryptedBackupRoundTripsEveryPersistedSetting() {
        val encrypted = SettingsBackupCodec.encrypt(settings, "correct horse battery staple")

        assertEquals(settings, SettingsBackupCodec.decrypt(encrypted, "correct horse battery staple"))
    }

    @Test
    fun encryptedFileContainsNeitherServerCredentialNorScaleConsentInPlaintext() {
        val encryptedText = SettingsBackupCodec.encrypt(settings, "correct horse battery staple").decodeToString()

        assertFalse("secret-session-cookie" in encryptedText)
        assertFalse("E7:DB:51:F1:36:91" in encryptedText)
        assertFalse("1234" in encryptedText)
    }

    @Test
    fun wrongPassphraseCannotDecryptBackup() {
        val encrypted = SettingsBackupCodec.encrypt(settings, "correct horse battery staple")

        assertThrows(Exception::class.java) {
            SettingsBackupCodec.decrypt(encrypted, "incorrect passphrase")
        }
    }

    @Test
    fun shortPassphraseIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupCodec.encrypt(settings, "short")
        }
    }

    // --- The backup carries the bearer token in cleartext once decrypted, and
    // MIN_PASSPHRASE_LENGTH is only 8, so the KDF cost is what stands between a
    // stolen export file and the credential. The iteration count is not written
    // into the file, so only re-deriving the key out-of-band can pin it down.

    @Test
    fun keyDerivationUsesTheOwaspIterationCountForItsSha256Prf() {
        val encrypted = SettingsBackupCodec.encrypt(settings, PASSPHRASE)

        val plaintext = decryptWith(encrypted, OWASP_SHA256_ITERATIONS).decodeToString()

        assertTrue("re-deriving at $OWASP_SHA256_ITERATIONS must reproduce the backup", "base_url" in plaintext)
        assertTrue("secret-session-cookie" in plaintext)
    }

    @Test
    fun keyDerivationNoLongerUsesTheIterationCountMeantForSha512() {
        val encrypted = SettingsBackupCodec.encrypt(settings, PASSPHRASE)

        assertThrows(AEADBadTagException::class.java) { decryptWith(encrypted, OWASP_SHA512_ITERATIONS) }
    }

    /** Decrypts a backup with a key derived independently of the codec, at [iterations]. */
    private fun decryptWith(encrypted: ByteArray, iterations: Int): ByteArray {
        val magic = "BASCULE1".toByteArray(StandardCharsets.US_ASCII)
        val buffer = ByteBuffer.wrap(encrypted)
        assertTrue(ByteArray(magic.size).also(buffer::get).contentEquals(magic))
        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val spec = PBEKeySpec(PASSPHRASE.toCharArray(), salt, iterations, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(magic)
        }.doFinal(ciphertext)
    }

    // --- TS-H5: a corrupt `profiles` field must abort the import, not read as "no profiles".

    @Test
    fun aProfilesFieldThatIsNotAnArrayIsRejectedRatherThanReadAsEmpty() {
        listOf("{}", "\"\"", "0", "\"[]\"").forEach { malformed ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                SettingsBackupCodec.decode(payload("profiles" to malformed))
            }
            assertEquals("Backup profile list is malformed", failure.message)
        }
    }

    @Test
    fun anAbsentOrNullProfilesFieldStillDecodesAsARegistryEraBackup() {
        listOf(payload("profiles" to "null"), payload("profiles" to "[]")).forEach { text ->
            val decoded = SettingsBackupCodec.decode(text)
            assertTrue(decoded.profiles.isEmpty())
            assertTrue("the format still carries a registry, so this is not a legacy backup", decoded.supportsProfiles)
        }
    }

    @Test
    fun aVersionOneBackupIsMarkedAsPredatingTheProfileRegistry() {
        val decoded = SettingsBackupCodec.decode(payload("version" to "1", "profiles" to "[]"))

        assertFalse(decoded.supportsProfiles)
    }

    // --- TS-H4: an enum name from a newer build is a version mismatch, not a bad passphrase.

    @Test
    fun anUnknownCredentialTypeFailsLoudlyRatherThanDroppingTheCredential() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupCodec.decode(payload("credential_type" to "\"PASSKEY\""))
        }

        assertTrue(
            "the message must point at a version mismatch, not the passphrase: ${failure.message}",
            failure.message.orEmpty().contains("not recognised by this app version"),
        )
    }

    @Test
    fun anUnknownDisplayUnitOrContractVersionDegradesToTheDefaultInsteadOfFailing() {
        val decoded = SettingsBackupCodec.decode(
            payload("display_unit" to "\"STONES\"", "contract_version" to "\"V9_EVERYTHING\""),
        )

        assertEquals(WeightUnit.KILOGRAMS, decoded.displayUnit)
        assertEquals(ContractVersion.V1_WEIGHT_ONLY, decoded.contractVersion)
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
        const val OWASP_SHA256_ITERATIONS = 600_000
        const val OWASP_SHA512_ITERATIONS = 210_000
        const val SALT_BYTES = 16
        const val IV_BYTES = 12
        const val KEY_BITS = 256
        const val GCM_TAG_BITS = 128
    }
}
