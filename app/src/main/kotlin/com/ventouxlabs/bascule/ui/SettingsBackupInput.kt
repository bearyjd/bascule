package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.data.SettingsBackupCodec
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The two validations standing between a user-chosen file, a user-typed
 * passphrase, and [SettingsBackupCodec]. Kept out of `ConfigScreen.kt` so the
 * JVM test lane can reach them — neither is a composable, and both guard
 * untrusted input.
 */

/**
 * Streams the picked document with the size cap enforced *during* the read, so
 * a file far larger than [SettingsBackupCodec.MAX_BACKUP_BYTES] cannot be
 * buffered into memory before being rejected.
 */
internal fun InputStream.readSettingsBackup(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= SettingsBackupCodec.MAX_BACKUP_BYTES) { "Settings backup is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/**
 * The app's only passphrase validation. [confirmation] is only consulted when
 * [confirmRequired] — the import dialog has one field, the export dialog two.
 *
 * [confirmRequired] therefore also marks the one dialog that *chooses* a
 * passphrase, and choosing is what
 * [SettingsBackupCodec.isPassphraseStrongEnough] governs. Unlocking keeps the
 * old floor: the strength rule must never be the reason a user cannot open a
 * backup this app itself wrote.
 */
internal fun isPassphraseValid(
    passphrase: String,
    confirmation: String,
    confirmRequired: Boolean,
): Boolean = if (confirmRequired) {
    SettingsBackupCodec.isPassphraseStrongEnough(passphrase) && passphrase == confirmation
} else {
    passphrase.length >= SettingsBackupCodec.MIN_PASSPHRASE_LENGTH
}
