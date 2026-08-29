package com.ventouxlabs.bascule.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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
                "$it must be transient — a redirect is a server config change, not a verdict on the reading",
                classify(it) is SubmitResult.TransientFailure,
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

    /**
     * Regression (round-3 HIGH #2). An over-large value used to be discarded,
     * which the drainer could not tell apart from "no `Retry-After` at all" — so
     * a hostile header made it burst the rest of the batch instead of parking it.
     * §4.5's ceiling is a clamp, not a reason to drop the header.
     */
    @Test
    fun anAbsurdRetryAfterIsClampedToTheCeilingRatherThanDiscarded() {
        val result = classify(503, retryAfter = "999999") as SubmitResult.TransientFailure
        assertEquals(1.hours, result.retryAfter)
    }

    /** RFC 9110 §10.2.3 allows an IMF-fixdate in place of delay-seconds. */
    @Test
    fun anHttpDateRetryAfterIsParsedAsADuration() {
        val now = Instant.parse("2026-10-21T07:26:00Z")

        val parsed = ResponseClassifier.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT", now)

        assertEquals(120.seconds, parsed)
    }

    @Test
    fun anHttpDateAlreadyInThePastMeansRetryNowNotIgnoreTheHeader() {
        val now = Instant.parse("2026-10-21T07:30:00Z")

        val parsed = ResponseClassifier.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT", now)

        assertEquals(Duration.ZERO, parsed)
    }

    @Test
    fun anHttpDateFarInTheFutureIsClampedToTheCeiling() {
        val now = Instant.parse("2026-10-21T07:28:00Z")

        val parsed = ResponseClassifier.parseRetryAfter("Fri, 21 Oct 2033 07:28:00 GMT", now)

        assertEquals(1.hours, parsed)
    }

    @Test
    fun aGenuinelyUnparseableRetryAfterYieldsNoDuration() {
        assertNull(ResponseClassifier.parseRetryAfter("soon-ish"))
        assertNull(ResponseClassifier.parseRetryAfter(null))
        assertNull(ResponseClassifier.parseRetryAfter("   "))
    }

    /**
     * Regression (round-3 HIGH #2). A rate limit with no usable `Retry-After` is
     * still a rate limit: without a duration the drainer resumed the pass and
     * submitted the rest of the batch straight back into the limiter.
     */
    @Test
    fun aRateLimitWithNoUsableRetryAfterStillBacksOff() {
        listOf(null, "soon-ish", "Not, A Date At All").forEach { header ->
            val result = classify(429, retryAfter = header) as SubmitResult.TransientFailure
            assertEquals("429 with header <$header> must still park the batch", 1.minutes, result.retryAfter)
        }
    }

    @Test
    fun anUnparseableRetryAfterOnANonRateLimitStatusStillBacksOff() {
        val result = classify(503, retryAfter = "soon-ish") as SubmitResult.TransientFailure

        assertEquals(1.minutes, result.retryAfter)
    }

    /** No header on an ordinary server error: the §3.4 ladder decides, not a guess. */
    @Test
    fun aServerErrorWithoutARetryAfterHeaderLeavesTheLadderInCharge() {
        val result = classify(503) as SubmitResult.TransientFailure

        assertNull(result.retryAfter)
    }

    @Test
    fun reasonStringsNeverEchoServerSuppliedText() {
        val permanent = classify(422) as SubmitResult.PermanentRejection
        val transient = classify(503, retryAfter = "10") as SubmitResult.TransientFailure

        assertEquals("rejected by server", permanent.reason)
        assertEquals("server returned 503", transient.reason)
    }
}
