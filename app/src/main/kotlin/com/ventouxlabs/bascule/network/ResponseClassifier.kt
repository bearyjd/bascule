package com.ventouxlabs.bascule.network

import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 00-design.md §4.5's status → [SubmitResult] table, in one place so the mapping
 * is testable without a socket.
 *
 * Reason strings are built from the status code and a fixed phrase only — never
 * from a response header or body, which could contain the token or the user's
 * body composition (00-design.md §8.8).
 */
object ResponseClassifier {

    private val MAX_HONOURED_RETRY_AFTER = 1.hours

    /**
     * Used when the server told us to slow down but not for how long. Parking
     * the batch on a guess is always safer than bursting the rest of it into a
     * rate limiter, and [DeliveryCoordinator][com.ventouxlabs.bascule.delivery.DeliveryCoordinator]
     * still bounds it.
     */
    private val DEFAULT_BACKOFF = 1.minutes

    fun classify(
        httpCode: Int,
        deliveredFields: Set<ReadingField>,
        retryAfterHeader: String? = null,
    ): SubmitResult = when {
        httpCode in SUCCESS_RANGE -> SubmitResult.Accepted(deliveredFields)
        httpCode in AUTH_CODES -> SubmitResult.AuthRejected(httpCode)
        // A redirect is a server-side configuration change (an http→https rule,
        // a moved endpoint), never a verdict on this reading. Failing it
        // permanently would march the whole pending queue to FAILED_PERMANENT
        // on the first attempt, so it retries until the URL is corrected.
        httpCode in REDIRECT_RANGE ->
            SubmitResult.TransientFailure(REDIRECT_REASON, transientRetryAfter(httpCode, retryAfterHeader))

        // Bascule only ever POSTs to a collection route (`/api/weight`), so a
        // 404 is never a verdict on this reading either — it can only mean the
        // endpoint is not there, which is a local base-URL configuration
        // error: VitalForge serves the weight routes under `/p/{slug}/`, and a
        // base URL missing that prefix authenticates fine and then finds
        // nothing. The redirect argument above applies unchanged, and it did
        // happen on hardware (2026-09-07): a real weigh-in went
        // FAILED_PERMANENT on its first attempt and only a contract toggle
        // recovered it. Retries stay bounded by DeliveryCoordinator.EXPIRY_MILLIS
        // exactly as for a redirect, and VitalForge's design spec (§f.8)
        // refuses to add root-path aliases, so the client is the only place
        // this can be fixed.
        httpCode == NOT_FOUND ->
            SubmitResult.TransientFailure(NO_SUCH_ENDPOINT_REASON, transientRetryAfter(httpCode, retryAfterHeader))

        httpCode in TRANSIENT_CODES || httpCode in SERVER_ERROR_RANGE ->
            SubmitResult.TransientFailure(
                "server returned $httpCode",
                transientRetryAfter(httpCode, retryAfterHeader),
            )

        httpCode in PERMANENT_CODES -> SubmitResult.PermanentRejection(httpCode, "rejected by server")
        // Any other 4xx is a client error the server will keep rejecting.
        httpCode in CLIENT_ERROR_RANGE -> SubmitResult.PermanentRejection(httpCode, "rejected by server")
        else -> SubmitResult.TransientFailure("unexpected status $httpCode", null)
    }

    /**
     * How long to park the batch for. A rate limit with no usable `Retry-After`
     * still parks: resuming the pass would submit the rest of the batch straight
     * back into the same limiter.
     */
    private fun transientRetryAfter(httpCode: Int, header: String?): Duration? =
        parseRetryAfter(header)
            ?: DEFAULT_BACKOFF.takeIf { httpCode == RATE_LIMITED || header != null }

    /**
     * `Retry-After` per RFC 9110 §10.2.3: delay-seconds or an IMF-fixdate. The
     * obsolete RFC 850 and asctime date forms are not accepted — no server in
     * practice sends them, and an unparsed header still backs off via
     * [transientRetryAfter] rather than bursting.
     *
     * An over-large value is clamped rather than discarded: discarding it made
     * the caller treat a hostile header as "no header at all", which resumed the
     * drain instead of parking it.
     */
    fun parseRetryAfter(header: String?, now: Instant = Instant.now()): Duration? {
        val trimmed = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val duration = trimmed.toLongOrNull()?.seconds ?: parseHttpDate(trimmed, now) ?: return null
        return duration.coerceIn(Duration.ZERO, MAX_HONOURED_RETRY_AFTER)
    }

    /** A date already in the past means "retry now", not "ignore this header". */
    private fun parseHttpDate(header: String, now: Instant): Duration? =
        runCatching { Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(header)) }
            .getOrNull()
            ?.let { (it.toEpochMilli() - now.toEpochMilli()).milliseconds }

    private const val REDIRECT_REASON =
        "redirect not followed; a moved endpoint is a configuration error"

    /**
     * Surfaced verbatim by `ConfigViewModel.testConnection()`, so it is
     * written for the person reading the Settings screen, not for a log.
     */
    const val NO_SUCH_ENDPOINT_REASON =
        "No such endpoint (404) — the Base URL may be missing your person path, e.g. /p/your-slug"

    private const val NOT_FOUND = 404
    private const val RATE_LIMITED = 429

    private val SUCCESS_RANGE = 200..299
    private val REDIRECT_RANGE = 300..399
    private val CLIENT_ERROR_RANGE = 400..499
    private val SERVER_ERROR_RANGE = 500..599
    private val AUTH_CODES = setOf(401, 403)
    private val TRANSIENT_CODES = setOf(408, 429)
    private val PERMANENT_CODES = setOf(400, 409, 413, 422)
}
