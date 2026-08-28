package com.ventouxlabs.bascule.delivery

import kotlin.time.Duration

/**
 * Delivery status transitions and the retry schedule. Holds no HTTP.
 */
object DeliveryCoordinator {

    /** ADR-005: only TRANSIENT failures age out, and only from retryEpochMillis. */
    const val EXPIRY_MILLIS = 14L * 24 * 60 * 60 * 1000

    /** 00-design.md §3.4 backoff ladder: 30 s doubling to a 15 min cap. */
    const val BACKOFF_BASE_MILLIS = 30_000L
    const val BACKOFF_CAP_MILLIS = 900_000L

    /**
     * 00-design.md §4.5 caps how long a server may park a row. The ladder tops
     * out at 15 min, so only a `Retry-After` can reach this bound at all — and a
     * hostile one must not be able to park a reading forever.
     */
    const val MAX_RETRY_AFTER_MILLIS = 60L * 60 * 1000

    /**
     * One page of a drain. Bounds a single [DeliveryWorker] run well inside
     * WorkManager's 10-minute execution ceiling: a backlog that would exceed it
     * (an unblocked `BLOCKED_AUTH` recovery, most plausibly) is finished by a
     * re-enqueued run instead of being killed mid-drain and restarted from the
     * top, re-walking every row it had already processed.
     */
    const val DRAIN_BATCH_LIMIT = 50

    /**
     * 00-design.md §3.4: `min(30 s * 2^(attemptCount - 1), 15 min)` — 30 s, 1 m,
     * 2 m, 4 m, 8 m, then 15 m forever. [attemptCount] is the count *including*
     * the attempt that just failed, so the first failure waits the 30 s base.
     *
     * Doubling by shift rather than `2.0.pow()`: at attemptCount 64 the exponent
     * would overflow a Long, and the cap is reached at 6 anyway.
     */
    fun backoffMillis(attemptCount: Int): Long {
        if (attemptCount <= 0) return 0
        val doublings = (attemptCount - 1).coerceAtMost(MAX_DOUBLINGS)
        return (BACKOFF_BASE_MILLIS shl doublings).coerceAtMost(BACKOFF_CAP_MILLIS)
    }

    /**
     * When a row that just failed transiently may next be submitted. A server's
     * own [retryAfter] wins over the ladder when it sent one — it knows about
     * its rate limit and we do not — bounded by [MAX_RETRY_AFTER_MILLIS].
     */
    fun nextAttemptMillis(now: Long, attemptCount: Int, retryAfter: Duration?): Long {
        val delay = retryAfter
            ?.inWholeMilliseconds
            ?.coerceIn(0, MAX_RETRY_AFTER_MILLIS)
            ?: backoffMillis(attemptCount)
        return now + delay
    }

    /** The number of doublings after which [BACKOFF_CAP_MILLIS] is reached regardless. */
    private const val MAX_DOUBLINGS = 30
}
