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

/** Why the passphrase dialog's confirm button is disabled — see [passphraseProblem]. */
internal enum class PassphraseProblem { TOO_SHORT, TOO_FEW_DISTINCT_CHARACTERS, CONFIRMATION_MISMATCH }

/**
 * The passphrase dialog's gate: the first reason the typed passphrase cannot
 * be used yet, or null when it can. [SettingsBackupCodec.encrypt] enforces the
 * same strength rule as a backstop. [confirmation] is only consulted when
 * [confirmRequired] — the import dialog has one field, the export dialog two.
 *
 * [confirmRequired] therefore also marks the one dialog that *chooses* a
 * passphrase, and choosing is what
 * [SettingsBackupCodec.isPassphraseStrongEnough] governs. Validity is decided
 * by that function alone; the length check here only picks which reason to
 * name. Unlocking keeps the old floor: the strength rule must never be the
 * reason a user cannot open a backup this app itself wrote.
 */
internal fun passphraseProblem(
    passphrase: String,
    confirmation: String,
    confirmRequired: Boolean,
): PassphraseProblem? = when {
    !confirmRequired ->
        PassphraseProblem.TOO_SHORT.takeIf { passphrase.length < SettingsBackupCodec.MIN_PASSPHRASE_LENGTH }
    !SettingsBackupCodec.isPassphraseStrongEnough(passphrase) ->
        if (passphrase.length < SettingsBackupCodec.MIN_NEW_PASSPHRASE_LENGTH) {
            PassphraseProblem.TOO_SHORT
        } else {
            PassphraseProblem.TOO_FEW_DISTINCT_CHARACTERS
        }
    passphrase != confirmation -> PassphraseProblem.CONFIRMATION_MISMATCH
    else -> null
}

/** Defined as the absence of a [passphraseProblem], so the gate and its message cannot disagree. */
internal fun isPassphraseValid(
    passphrase: String,
    confirmation: String,
    confirmRequired: Boolean,
): Boolean = passphraseProblem(passphrase, confirmation, confirmRequired) == null

/**
 * The line shown under the field a [PassphraseProblem] is about. Without it a
 * greyed-out Export button was the only feedback, and the distinct-character
 * floor was stated nowhere the user could see it.
 */
internal fun PassphraseProblem.message(confirmRequired: Boolean): String = when (this) {
    PassphraseProblem.TOO_SHORT -> if (confirmRequired) {
        "Use at least ${SettingsBackupCodec.MIN_NEW_PASSPHRASE_LENGTH} characters."
    } else {
        "Backup passphrases are at least ${SettingsBackupCodec.MIN_PASSPHRASE_LENGTH} characters."
    }
    PassphraseProblem.TOO_FEW_DISTINCT_CHARACTERS ->
        "Use at least ${SettingsBackupCodec.MIN_NEW_PASSPHRASE_DISTINCT_CHARACTERS} different characters."
    PassphraseProblem.CONFIRMATION_MISMATCH -> "Passphrases don't match."
}
