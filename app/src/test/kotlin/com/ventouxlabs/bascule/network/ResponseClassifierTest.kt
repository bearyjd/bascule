package com.ventouxlabs.bascule.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** 00-design.md §4.5's classification table, exhaustively. */
class ResponseClassifierTest {

    private val fields = setOf(ReadingField.WEIGHT)

    private fun classify(code: Int, retryAfter: String? = null) =
        ResponseClassifier.classify(code, fields, retryAfter)

    @Test
    fun everyStatusCodeMapsToTheDocumentedResult() {
        listOf(200, 201, 202, 204).forEach {
            assertTrue("$it should be Accepted", classify(it) is SubmitResult.Accepted)
        }
        listOf(301, 302, 307, 308).forEach {
            assertTrue(
                "$it must be permanent — a redirect is never followed",
                classify(it) is SubmitResult.PermanentRejection,
            )
        }
        listOf(401, 403).forEach {
            assertTrue("$it should be AuthRejected", classify(it) is SubmitResult.AuthRejected)
        }
        listOf(408, 429, 500, 502, 503, 504).forEach {
            assertTrue("$it should be transient", classify(it) is SubmitResult.TransientFailure)
        }
        listOf(400, 404, 409, 413, 422).forEach {
            assertTrue(
                "$it should be permanent — retrying a rejected body never succeeds",
                classify(it) is SubmitResult.PermanentRejection,
            )
        }
    }

    @Test
    fun acceptedCarriesTheFieldsThatWereActuallySent() {
        assertEquals(fields, (classify(200) as SubmitResult.Accepted).deliveredFields)
    }

    @Test
    fun authRejectionDoesNotBurnTheRetryBudget() {
        // A rotated token must not march the backlog to FAILED_PERMANENT
        // (ADR-005); it is a distinct result so the drain can pause globally.
        val result = classify(401)
        assertTrue(result is SubmitResult.AuthRejected)
        assertEquals(401, (result as SubmitResult.AuthRejected).httpCode)
    }

    @Test
    fun retryAfterIsHonouredWhenSane() {
        val result = classify(429, retryAfter = "120") as SubmitResult.TransientFailure
        assertEquals(120.seconds, result.retryAfter)
    }

    @Test
    fun absurdRetryAfterIsIgnoredRatherThanParkingTheQueue() {
        val result = classify(503, retryAfter = "999999") as SubmitResult.TransientFailure
        assertNull(result.retryAfter)
    }

    @Test
    fun nonNumericRetryAfterIsIgnored() {
        assertNull(ResponseClassifier.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun reasonStringsNeverEchoServerSuppliedText() {
        val permanent = classify(422) as SubmitResult.PermanentRejection
        val transient = classify(503, retryAfter = "10") as SubmitResult.TransientFailure

        assertEquals("rejected by server", permanent.reason)
        assertEquals("server returned 503", transient.reason)
    }
}
