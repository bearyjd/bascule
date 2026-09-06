package com.ventouxlabs.bascule.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy behind the one notification the app posts on its own: which
 * outcomes need a human, and when the slot changes rather than nags.
 */
class CaptureAttentionTest {

    @Test
    fun onlyTheTwoOutcomesAHumanMustFixNeedAttention() {
        val needing = CaptureOutcome.entries.filter { attentionFor(it) != null }.toSet()

        assertEquals(setOf(CaptureOutcome.NEEDS_PAIRING, CaptureOutcome.NOT_READY), needing)
    }

    @Test
    fun aNewProblemIsPosted() {
        val t = attentionTransition(previous = CaptureOutcome.IDLE, current = CaptureOutcome.NEEDS_PAIRING)

        assertTrue(t is AttentionTransition.Post)
        assertEquals(attentionFor(CaptureOutcome.NEEDS_PAIRING), (t as AttentionTransition.Post).attention)
    }

    @Test
    fun theFirstEverAttemptCanPost() {
        assertTrue(attentionTransition(previous = null, current = CaptureOutcome.NOT_READY) is AttentionTransition.Post)
    }

    /**
     * The pairing case recurs every session until the user acts; re-posting
     * each time is the nag that gets an app muted.
     */
    @Test
    fun theSameProblemAgainIsNotRePosted() {
        assertEquals(
            AttentionTransition.None,
            attentionTransition(previous = CaptureOutcome.NEEDS_PAIRING, current = CaptureOutcome.NEEDS_PAIRING),
        )
    }

    @Test
    fun aDifferentProblemReplacesTheFirst() {
        val t = attentionTransition(previous = CaptureOutcome.NEEDS_PAIRING, current = CaptureOutcome.NOT_READY)

        assertTrue(t is AttentionTransition.Post)
        assertEquals(attentionFor(CaptureOutcome.NOT_READY), (t as AttentionTransition.Post).attention)
    }

    @Test
    fun aResolvedProblemClearsTheNotification() {
        assertEquals(
            AttentionTransition.Clear,
            attentionTransition(previous = CaptureOutcome.NEEDS_PAIRING, current = CaptureOutcome.CAPTURED),
        )
        assertEquals(
            AttentionTransition.Clear,
            attentionTransition(previous = CaptureOutcome.NOT_READY, current = CaptureOutcome.IDLE),
        )
    }

    @Test
    fun benignToBenignTouchesNothing() {
        assertEquals(
            AttentionTransition.None,
            attentionTransition(previous = CaptureOutcome.IDLE, current = CaptureOutcome.NO_READING),
        )
        assertEquals(AttentionTransition.None, attentionTransition(previous = null, current = CaptureOutcome.CAPTURED))
    }

    @Test
    fun attentionTextsAreNonEmptyAndActionable() {
        listOf(CaptureOutcome.NEEDS_PAIRING, CaptureOutcome.NOT_READY).forEach {
            val a = attentionFor(it)
            assertNotNull(a)
            assertTrue(a!!.title.isNotBlank() && a.body.isNotBlank())
        }
        assertNull(attentionFor(CaptureOutcome.IDLE))
    }
}
