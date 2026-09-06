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
        { now },
        BACKOFF_MILLIS,
    )

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

    /**
     * The lockout this whole mechanism existed to cause, now closed. A session
     * that never reached the radio — the staleness abort, overwhelmingly — used
     * to hold the address for the full five minutes, so the user standing on
     * the scale generated advertisements that could not enqueue anything and
     * saw nothing happen until long after they had stepped off.
     */
    @Test
    fun releasingLetsTheVeryNextAdvertisementThrough() {
        cooldown.claim(ADDRESS)

        cooldown.settle(ADDRESS, CooldownDisposition.RELEASE)

        assertTrue("a released address must be claimable immediately", cooldown.claim(ADDRESS))
    }

    /** A release must also leave no entry behind, for the pruning reason above. */
    @Test
    fun releasingRemovesTheEntryFromDisk() {
        cooldown.claim(ADDRESS)

        cooldown.settle(ADDRESS, CooldownDisposition.RELEASE)

        val keys = context.getSharedPreferences("scan_enqueue_cooldown", Context.MODE_PRIVATE).all.keys
        assertFalse(keys.contains(ADDRESS))
    }

    /**
     * A session that did reach the radio and failed must not be reconnected to
     * on the next packet of the same burst — that is the 2-10/s reconnect storm
     * this file exists to prevent.
     */
    @Test
    fun aBackoffStillSuppressesTheRestOfTheAdvertisementBurst() {
        cooldown.claim(ADDRESS)
        cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
        now += BACKOFF_MILLIS - 1

        assertFalse(cooldown.claim(ADDRESS))
    }

    /**
     * ...but it must expire in seconds rather than minutes, so stepping off,
     * waiting, and stepping back on is a real second attempt.
     */
    @Test
    fun aBackoffExpiresLongBeforeTheFullWindowWould() {
        cooldown.claim(ADDRESS)
        cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
        now += BACKOFF_MILLIS

        assertTrue("a failed session must not cost the full window", cooldown.claim(ADDRESS))
    }

    /** A captured reading keeps the full window: re-running would only re-capture it. */
    @Test
    fun holdingKeepsTheFullWindow() {
        cooldown.claim(ADDRESS)
        cooldown.settle(ADDRESS, CooldownDisposition.HOLD)
        now += WINDOW_MILLIS - 1

        assertFalse(cooldown.claim(ADDRESS))
    }

    /** A backoff longer than the window itself must not resurrect a claim early. */
    @Test
    fun aBackoffLongerThanTheWindowIsClampedToIt() {
        val clamped = ScanEnqueueCooldown(
            context.getSharedPreferences("scan_enqueue_cooldown", Context.MODE_PRIVATE),
            WINDOW_MILLIS,
            { now },
            WINDOW_MILLIS * 10,
        )
        clamped.claim(ADDRESS)

        clamped.settle(ADDRESS, CooldownDisposition.BACKOFF)
        now += WINDOW_MILLIS - 1

        assertFalse(clamped.claim(ADDRESS))
    }

    /** Settling one address must not disturb another's window. */
    @Test
    fun settlingOneAddressLeavesAnotherUntouched() {
        cooldown.claim(ADDRESS)
        cooldown.claim(OTHER_ADDRESS)

        cooldown.settle(ADDRESS, CooldownDisposition.RELEASE)

        assertFalse(cooldown.claim(OTHER_ADDRESS))
    }

    /**
     * Observed on hardware before this existed: a scale that advertises
     * continuously with no measurement to give (the phone is near it, nobody is
     * standing on it) was reconnected to every ~70s indefinitely — the
     * reconnect storm this file exists to prevent, merely slower.
     */
    @Test
    fun consecutiveFailuresBackOffProgressively() {
        cooldown.claim(ADDRESS)
        cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
        now += BACKOFF_MILLIS
        assertTrue("the first retry must stay quick", cooldown.claim(ADDRESS))

        cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
        now += BACKOFF_MILLIS
        assertFalse("a second straight failure must wait longer than the first", cooldown.claim(ADDRESS))

        now += BACKOFF_MILLIS
        assertTrue(cooldown.claim(ADDRESS))
    }

    /** A success ends the streak: the next failure is a first failure again. */
    @Test
    fun aCaptureResetsTheEscalation() {
        cooldown.claim(ADDRESS)
        repeat(3) {
            cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
            now += WINDOW_MILLIS
            cooldown.claim(ADDRESS)
        }

        cooldown.settle(ADDRESS, CooldownDisposition.HOLD)
        now += WINDOW_MILLIS
        cooldown.claim(ADDRESS)
        cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
        now += BACKOFF_MILLIS

        assertTrue("a failure after a capture must back off as a first failure", cooldown.claim(ADDRESS))
    }

    /** However long the streak, the quiet period never exceeds the full window. */
    @Test
    fun escalationIsCappedAtTheFullWindow() {
        cooldown.claim(ADDRESS)
        repeat(12) {
            cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
            now += WINDOW_MILLIS
            cooldown.claim(ADDRESS)
        }

        cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
        now += WINDOW_MILLIS

        assertTrue("backoff must never outlast the window itself", cooldown.claim(ADDRESS))
    }

    /**
     * The escape hatch. "Weigh now" means the user is on the scale right now,
     * and a throttle earned by earlier failures must not be what stops it.
     */
    @Test
    fun clearingLetsAThrottledAddressThroughImmediately() {
        cooldown.claim(ADDRESS)
        repeat(4) {
            cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
            now += WINDOW_MILLIS
            cooldown.claim(ADDRESS)
        }
        cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)

        cooldown.clear(ADDRESS)

        assertTrue("an explicit weigh-now must never be throttled", cooldown.claim(ADDRESS))
    }

    /** Clearing must also end the streak, not just the current window. */
    @Test
    fun clearingAlsoResetsTheEscalation() {
        cooldown.claim(ADDRESS)
        repeat(4) {
            cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
            now += WINDOW_MILLIS
            cooldown.claim(ADDRESS)
        }

        cooldown.clear(ADDRESS)
        cooldown.claim(ADDRESS)
        cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
        now += BACKOFF_MILLIS

        assertTrue("a cleared address must back off as a first failure", cooldown.claim(ADDRESS))
    }

    /**
     * The streak counters share the file with the claim stamps, and [claim]'s
     * pruning pass reads every other key as a timestamp — so they must not be
     * mistaken for one, nor survive the address they belong to.
     */
    @Test
    fun streakCountersDoNotDisturbPruningAndDoNotOutliveTheirAddress() {
        cooldown.claim(ADDRESS)
        cooldown.settle(ADDRESS, CooldownDisposition.BACKOFF)
        now += WINDOW_MILLIS

        cooldown.claim(OTHER_ADDRESS)

        val keys = context.getSharedPreferences("scan_enqueue_cooldown", Context.MODE_PRIVATE).all.keys
        assertFalse("the expired address must be pruned", keys.contains(ADDRESS))
        assertFalse("its streak must be pruned with it", keys.contains("fails:$ADDRESS"))
        assertTrue(keys.contains(OTHER_ADDRESS))
    }

    private companion object {
        const val WINDOW_MILLIS = 5L * 60 * 1_000
        const val BACKOFF_MILLIS = 20L * 1_000
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val OTHER_ADDRESS = "11:22:33:44:55:66"
    }
}
