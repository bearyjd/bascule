package com.ventouxlabs.bascule.delivery

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** 00-design.md §3.4's ladder, stated as the doc states it: 30 s, 1 m, 2 m, 4 m, 8 m, then 15 m forever. */
class DeliveryCoordinatorTest {

    @Test
    fun theLadderDoublesFromThirtySecondsAndCapsAtFifteenMinutes() {
        assertEquals(30_000L, DeliveryCoordinator.backoffMillis(1))
        assertEquals(60_000L, DeliveryCoordinator.backoffMillis(2))
        assertEquals(120_000L, DeliveryCoordinator.backoffMillis(3))
        assertEquals(240_000L, DeliveryCoordinator.backoffMillis(4))
        assertEquals(480_000L, DeliveryCoordinator.backoffMillis(5))
        assertEquals(900_000L, DeliveryCoordinator.backoffMillis(6))
        assertEquals(
            "the cap holds forever, not just for one more step",
            900_000L,
            DeliveryCoordinator.backoffMillis(7),
        )
    }

    /**
     * ADR-005 keeps rows retriable for 14 days — roughly 1 300 attempts at the
     * capped interval. The exponent must not overflow anywhere in that range, nor
     * beyond it: a `Long` shifted 64 times wraps back to a small positive number,
     * which would silently turn the cap into no backoff at all.
     */
    @Test
    fun aVeryHighAttemptCountStaysAtTheCapRatherThanOverflowing() {
        assertEquals(900_000L, DeliveryCoordinator.backoffMillis(1_300))
        assertEquals(900_000L, DeliveryCoordinator.backoffMillis(Int.MAX_VALUE))
    }

    @Test
    fun aRowThatHasNeverBeenAttemptedIsDueImmediately() {
        assertEquals(0L, DeliveryCoordinator.backoffMillis(0))
    }

    @Test
    fun aServerRetryAfterWinsOverTheLadder() {
        assertEquals(
            "the server knows about its own rate limit and the ladder does not",
            1_000L + 120_000L,
            DeliveryCoordinator.nextAttemptMillis(now = 1_000L, attemptCount = 1, retryAfter = 120.seconds),
        )
    }

    /** §4.5: a hostile `Retry-After` must not be able to park a reading indefinitely. */
    @Test
    fun anAbsurdRetryAfterIsClampedToOneHour() {
        assertEquals(
            DeliveryCoordinator.MAX_RETRY_AFTER_MILLIS,
            DeliveryCoordinator.nextAttemptMillis(now = 0L, attemptCount = 1, retryAfter = 30.days),
        )
    }

    @Test
    fun theLadderIsUsedWhenTheServerSentNoRetryAfter() {
        assertEquals(
            1_000L + 30_000L,
            DeliveryCoordinator.nextAttemptMillis(now = 1_000L, attemptCount = 1, retryAfter = null),
        )
    }
}
