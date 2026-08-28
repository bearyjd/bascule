package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.data.SettingsBackupCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * C8: both of these were private to `ConfigScreen.kt`, reachable only through
 * a file-picker callback. One guards untrusted user-selected file input; the
 * other is the app's only passphrase validation.
 */
class SettingsBackupInputTest {

    private fun bytes(size: Int) = ByteArray(size) { (it % Byte.MAX_VALUE).toByte() }

    // --- readSettingsBackup: the size cap on an untrusted file.

    @Test
    fun readsAStreamSmallerThanTheCapVerbatim() {
        val content = bytes(1024)

        assertArrayEquals(content, ByteArrayInputStream(content).readSettingsBackup())
    }

    @Test
    fun acceptsAStreamOfExactlyTheCap() {
        val content = bytes(SettingsBackupCodec.MAX_BACKUP_BYTES)

        assertEquals(
            "the bound is inclusive — a backup this app itself produced at the limit must still import",
            SettingsBackupCodec.MAX_BACKUP_BYTES,
            ByteArrayInputStream(content).readSettingsBackup().size,
        )
    }

    @Test
    fun rejectsAStreamOneByteOverTheCap() {
        val content = bytes(SettingsBackupCodec.MAX_BACKUP_BYTES + 1)

        assertThrows(IllegalArgumentException::class.java) {
            ByteArrayInputStream(content).readSettingsBackup()
        }
    }

    /**
     * The cap is enforced *during* the read, so a stream that never ends is
     * rejected after roughly a megabyte rather than exhausting the heap. An
     * endless stream is what a hostile content provider behind the document
     * picker actually looks like.
     */
    @Test
    fun rejectsAnEndlessStreamWithoutBufferingItAll() {
        val endless = object : InputStream() {
            var served = 0
                private set

            override fun read(): Int = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }

        assertThrows(IllegalArgumentException::class.java) { endless.readSettingsBackup() }
        assertTrue(
            "the cap must bite while reading, not after",
            endless.served <= SettingsBackupCodec.MAX_BACKUP_BYTES + DEFAULT_BUFFER_SIZE,
        )
    }

    /**
     * The two failures are different exception types, so the caller *can*
     * distinguish "too large" from "unreadable" and show the accurate message
     * — `ConfigScreen`'s `runCatching { … }.getOrElse` currently collapses both
     * into "Could not read the selected file." (report finding C8). Pinning the
     * types here is what makes fixing that a one-line change.
     */
    @Test
    fun anOversizeFailureIsADifferentExceptionFromAnUnreadableOne() {
        val oversize = ByteArrayInputStream(bytes(SettingsBackupCodec.MAX_BACKUP_BYTES + 1))
        val unreadable = object : InputStream() {
            override fun read(): Int = throw IOException("device disconnected")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("device disconnected")
        }

        val tooLarge = assertThrows(IllegalArgumentException::class.java) { oversize.readSettingsBackup() }
        assertThrows(IOException::class.java) { unreadable.readSettingsBackup() }
        assertEquals("Settings backup is too large", tooLarge.message)
    }

    @Test
    fun readsAnEmptyStreamAsAnEmptyArray() {
        assertEquals(0, ByteArrayInputStream(ByteArray(0)).readSettingsBackup().size)
    }

    // --- isPassphraseValid: the length floor, and the export dialog's confirmation field.

    @Test
    fun rejectsAPassphraseOneCharacterShortOfTheFloor() {
        val short = "x".repeat(SettingsBackupCodec.MIN_PASSPHRASE_LENGTH - 1)

        assertFalse(isPassphraseValid(short, short, confirmRequired = false))
        assertFalse(isPassphraseValid(short, short, confirmRequired = true))
    }

    @Test
    fun acceptsAPassphraseOfExactlyTheFloor() {
        val exact = "x".repeat(SettingsBackupCodec.MIN_PASSPHRASE_LENGTH)

        assertTrue(isPassphraseValid(exact, exact, confirmRequired = false))
        assertTrue(isPassphraseValid(exact, exact, confirmRequired = true))
    }

    @Test
    fun rejectsAnEmptyPassphrase() {
        assertFalse(isPassphraseValid("", "", confirmRequired = false))
    }

    /**
     * Export confirms, import does not: an import dialog has one field, so a
     * blank confirmation must not block a valid passphrase.
     */
    @Test
    fun ignoresTheConfirmationFieldWhenConfirmationIsNotRequired() {
        assertTrue(isPassphraseValid("correct horse battery", "", confirmRequired = false))
    }

    @Test
    fun rejectsAMismatchedConfirmationWhenConfirmationIsRequired() {
        assertFalse(
            "a typo in the confirm field would otherwise encrypt the backup with an unrecoverable passphrase",
            isPassphraseValid("correct horse battery", "correct horse batttery", confirmRequired = true),
        )
    }
}
