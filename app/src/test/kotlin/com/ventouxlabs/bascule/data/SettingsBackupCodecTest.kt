package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.network.ContractVersion
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
}
