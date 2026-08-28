package com.ventouxlabs.bascule.network

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The master key is shared by every encrypted store, so deleting it resets the
 * auth token, the session cookie, every scale consent code and the profile
 * registry at once. These cover the ladder that keeps that cascade from firing
 * for anything short of a master key that is genuinely unusable.
 */
class EncryptedPreferencesRecoveryTest {

    /** Fails the first [failures] builds, then succeeds. Records recovery steps in order. */
    private class Builder(private val failures: Int) {
        var attempts = 0
            private set
        val steps = mutableListOf<String>()

        fun build(): String {
            attempts++
            if (attempts <= failures) throw IOException("keyset unreadable on attempt $attempts")
            return "prefs"
        }

        fun run(): String = buildWithRecovery(
            build = ::build,
            deleteFile = { steps += "deleteFile" },
            deleteMasterKey = { steps += "deleteMasterKey" },
        )
    }

    @Test
    fun aStoreThatOpensNormallyDiscardsNothing() {
        val builder = Builder(failures = 0)

        assertEquals("prefs", builder.run())
        assertEquals(1, builder.attempts)
        assertTrue(builder.steps.isEmpty())
    }

    @Test
    fun aSingleTransientFailureIsRetriedInsteadOfDestroyingCredentials() {
        val builder = Builder(failures = 1)

        assertEquals("prefs", builder.run())
        assertEquals(2, builder.attempts)
        assertTrue("a retryable Keystore hiccup must not delete anything", builder.steps.isEmpty())
    }

    @Test
    fun aFailureSurvivingTheRetryDiscardsOnlyThisStoresOwnFile() {
        val builder = Builder(failures = 2)

        assertEquals("prefs", builder.run())
        assertEquals(3, builder.attempts)
        assertEquals(listOf("deleteFile"), builder.steps)
        assertFalse("one store's corrupt file must not reset the other stores", "deleteMasterKey" in builder.steps)
    }

    @Test
    fun onlyAFailureThatOutlivesTheFileDeletionImplicatesTheSharedMasterKey() {
        val builder = Builder(failures = 3)

        assertEquals("prefs", builder.run())
        assertEquals(4, builder.attempts)
        assertEquals(listOf("deleteFile", "deleteMasterKey"), builder.steps)
    }

    @Test
    fun anUnrecoverableStoreRethrowsRatherThanFallingBackToPlaintext() {
        val builder = Builder(failures = Int.MAX_VALUE)

        val failure = assertThrows(IOException::class.java) { builder.run() }

        assertEquals("keyset unreadable on attempt 4", failure.message)
        assertEquals(listOf("deleteFile", "deleteMasterKey"), builder.steps)
    }

    @Test
    fun aDeletionThatItselfFailsDoesNotAbortTheRemainingRecovery() {
        var attempts = 0

        val result = buildWithRecovery(
            build = {
                attempts++
                if (attempts <= 3) throw IOException("keyset unreadable") else "prefs"
            },
            deleteFile = { throw IllegalStateException("prefs file is held open") },
            deleteMasterKey = { },
        )

        assertEquals("prefs", result)
        assertEquals(4, attempts)
    }
}
