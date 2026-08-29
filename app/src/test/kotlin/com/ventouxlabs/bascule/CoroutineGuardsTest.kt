package com.ventouxlabs.bascule

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [runNonCancelling] replaced five hand-written copies of this exact
 * cancellation-safety shape (devil's-advocate review, maintainability round
 * 3) — a plain, Android-free JUnit test is deliberate: the whole point of the
 * extraction was a seam none of the original five call sites had.
 */
class CoroutineGuardsTest {

    @Test
    fun aSuccessfulBlockReturnsItsValueWithoutTouchingOnError() {
        var onErrorCalled = false

        val result = runNonCancelling(onError = { onErrorCalled = true; -1 }) { 42 }

        assertEquals(42, result)
        assertFalse(onErrorCalled)
    }

    @Test
    fun anExceptionIsRoutedToOnErrorRatherThanEscaping() {
        val caught = runNonCancelling(onError = { it.message }) {
            throw IllegalStateException("boom")
        }

        assertEquals("boom", caught)
    }

    @Test
    fun anErrorIsRoutedToOnErrorLikeAnyOtherThrowable() {
        val caught = runNonCancelling(onError = { it }) {
            throw OutOfMemoryError("simulated")
        }

        assertTrue(caught is OutOfMemoryError)
    }

    @Test(expected = CancellationException::class)
    fun aCancellationExceptionAlwaysPropagatesRatherThanReachingOnError() {
        runNonCancelling(onError = { error("onError must not run for CancellationException") }) {
            throw CancellationException("cancelled")
        }
    }
}
