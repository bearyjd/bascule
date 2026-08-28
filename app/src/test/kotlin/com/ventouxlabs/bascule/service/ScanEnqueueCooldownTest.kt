package com.ventouxlabs.bascule.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard against pr-1-review-performance.md C1: an advertisement burst
 * (2-10/s while the scale is in range) must not enqueue a GATT session per
 * packet, and a session must not be re-enqueued the instant the previous one
 * finishes.
 */
class ScanEnqueueCooldownTest {

    private var now = 0L
    private val cooldown = ScanEnqueueCooldown(WINDOW_MILLIS) { now }

    @Test
    fun theFirstSightingOfAnAddressIsClaimed() {
        assertTrue(cooldown.claim(ADDRESS))
    }

    @Test
    fun anAdvertisementBurstClaimsExactlyOnce() {
        val claimed = (1..50).count {
            now += 100
            cooldown.claim(ADDRESS)
        }

        assertEquals("50 packets over 5s must produce one session, not 50", 1, claimed)
    }

    @Test
    fun aSightingInsideTheWindowIsSuppressed() {
        cooldown.claim(ADDRESS)
        now += WINDOW_MILLIS - 1

        assertFalse(cooldown.claim(ADDRESS))
    }

    @Test
    fun aSightingAfterTheWindowIsClaimedAgain() {
        cooldown.claim(ADDRESS)
        now += WINDOW_MILLIS

        assertTrue(cooldown.claim(ADDRESS))
    }

    @Test
    fun theWindowRestartsFromTheClaimNotFromTheFirstSighting() {
        cooldown.claim(ADDRESS)
        now += WINDOW_MILLIS
        cooldown.claim(ADDRESS)
        now += WINDOW_MILLIS - 1

        assertFalse(cooldown.claim(ADDRESS))
    }

    @Test
    fun aSecondAddressIsTrackedIndependently() {
        cooldown.claim(ADDRESS)

        assertTrue(cooldown.claim(OTHER_ADDRESS))
    }

    private companion object {
        const val WINDOW_MILLIS = 5L * 60 * 1_000
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val OTHER_ADDRESS = "11:22:33:44:55:66"
    }
}
