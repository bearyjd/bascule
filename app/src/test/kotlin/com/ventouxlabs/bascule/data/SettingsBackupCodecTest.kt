package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.network.ContractVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    )

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
}
