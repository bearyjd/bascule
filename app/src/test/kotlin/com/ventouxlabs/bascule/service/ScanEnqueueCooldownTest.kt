package com.ventouxlabs.bascule.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guard against pr-1-review-performance.md C1: an advertisement burst
 * (2-10/s while the scale is in range) must not enqueue a GATT session per
 * packet, and a session must not be re-enqueued the instant the previous one
 * finishes.
 *
 * Robolectric now, rather than a plain JVM test, because the window is stored
 * in [android.content.SharedPreferences] — pr-1-review-round3.md MEDIUM #16
 * needs it shared with `ScanBroadcastReceiver`, which the framework rebuilds
 * per broadcast in a process it may have cold started, so an in-memory map
 * cannot hold the window for the path that needs it most.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScanEnqueueCooldownTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var now = 0L
    private val cooldown = cooldown()

    private fun cooldown() = ScanEnqueueCooldown(
        context.getSharedPreferences("scan_enqueue_cooldown", Context.MODE_PRIVATE),
        WINDOW_MILLIS,
    ) { now }

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

    /**
     * The reason for the move to disk: the two wake paths never share an
     * instance — `ScanBroadcastReceiver` is rebuilt per broadcast — so the
     * window has to outlive the object that opened it.
     */
    @Test
    fun theWindowIsHeldAcrossInstances() {
        assertTrue(cooldown.claim(ADDRESS))

        assertFalse("a fresh instance must see the open window", cooldown().claim(ADDRESS))
    }

    /**
     * A stamp on disk outlives a reboot, so a wall clock corrected backwards
     * would otherwise suppress every claim until real time caught up.
     */
    @Test
    fun aClockCorrectedBackwardsDoesNotSuppressIndefinitely() {
        now = WINDOW_MILLIS * 10
        cooldown.claim(ADDRESS)
        now = 0

        assertTrue(cooldown.claim(ADDRESS))
    }

    /**
     * Devil's-advocate review, security round 5: this file is durable, unlike
     * the in-memory map it replaced, so an expired entry left behind would
     * accumulate as a small permanent plaintext record of every scale this
     * device has ever cooled down for.
     */
    @Test
    fun anExpiredEntryIsPrunedWhenAnotherAddressIsClaimed() {
        cooldown.claim(ADDRESS)
        now += WINDOW_MILLIS

        cooldown.claim(OTHER_ADDRESS)

        val keys = context.getSharedPreferences("scan_enqueue_cooldown", Context.MODE_PRIVATE).all.keys
        assertFalse("expired address must not persist past its own window", keys.contains(ADDRESS))
        assertTrue(keys.contains(OTHER_ADDRESS))
    }

    /** Pruning must only ever remove entries whose own window has elapsed. */
    @Test
    fun aStillLiveEntrySurvivesPruning() {
        cooldown.claim(ADDRESS)
        now += WINDOW_MILLIS - 1

        cooldown.claim(OTHER_ADDRESS)

        val keys = context.getSharedPreferences("scan_enqueue_cooldown", Context.MODE_PRIVATE).all.keys
        assertTrue("an address still inside its own window must not be pruned", keys.contains(ADDRESS))
    }

    private companion object {
        const val WINDOW_MILLIS = 5L * 60 * 1_000
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val OTHER_ADDRESS = "11:22:33:44:55:66"
    }
}
