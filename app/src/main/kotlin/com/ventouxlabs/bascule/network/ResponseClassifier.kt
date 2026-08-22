package com.ventouxlabs.bascule.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
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

    fun classify(
        httpCode: Int,
        deliveredFields: Set<ReadingField>,
        retryAfterHeader: String? = null,
    ): SubmitResult = when {
        httpCode in SUCCESS_RANGE -> SubmitResult.Accepted(deliveredFields)
        httpCode in REDIRECT_RANGE -> SubmitResult.PermanentRejection(httpCode, REDIRECT_REASON)
        httpCode in AUTH_CODES -> SubmitResult.AuthRejected(httpCode)
        httpCode in TRANSIENT_CODES || httpCode in SERVER_ERROR_RANGE ->
            SubmitResult.TransientFailure(
                "server returned $httpCode",
                parseRetryAfter(retryAfterHeader),
            )

        httpCode in PERMANENT_CODES -> SubmitResult.PermanentRejection(httpCode, "rejected by server")
        // Any other 4xx is a client error the server will keep rejecting.
        httpCode in CLIENT_ERROR_RANGE -> SubmitResult.PermanentRejection(httpCode, "rejected by server")
        else -> SubmitResult.TransientFailure("unexpected status $httpCode", null)
    }

    /** Honoured only when it is sane; a hostile `Retry-After` must not park the queue. */
    fun parseRetryAfter(header: String?): Duration? {
        val seconds = header?.trim()?.toLongOrNull() ?: return null
        if (seconds <= 0) return null
        val duration = seconds.seconds
        return duration.takeIf { it <= MAX_HONOURED_RETRY_AFTER }
    }

    private const val REDIRECT_REASON =
        "redirect not followed; a moved endpoint is a configuration error"

    private val SUCCESS_RANGE = 200..299
    private val REDIRECT_RANGE = 300..399
    private val CLIENT_ERROR_RANGE = 400..499
    private val SERVER_ERROR_RANGE = 500..599
    private val AUTH_CODES = setOf(401, 403)
    private val TRANSIENT_CODES = setOf(408, 429)
    private val PERMANENT_CODES = setOf(400, 404, 409, 413, 422)
}
