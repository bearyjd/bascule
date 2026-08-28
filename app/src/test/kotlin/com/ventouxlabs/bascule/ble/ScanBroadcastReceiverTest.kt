package com.ventouxlabs.bascule.ble

import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.ble.fake.FakeScaleSessionEnqueuer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WP-08's named tests for [ScanBroadcastReceiver] (`docs/prp/01-plan.md:890-900`).
 *
 * `application = Application::class` avoids Robolectric instantiating the
 * real `BasculeApplication` — this receiver only ever needs a [FakeScaleSessionEnqueuer]
 * now (see `.claude/PRPs/plans/scale-admin-testing-completeness.plan.md` Task 3's
 * revision note for why a real `WorkManager`-backed test was not viable in
 * this environment: `WorkManagerTestInitHelper`'s Room-backed persistence
 * hits a native-SQLite `UnsatisfiedLinkError` under this project's pinned
 * Robolectric version, a documented upstream Robolectric limitation, not a
 * bug in this test).
 *
 * `onReceive` now dispatches on `Dispatchers.IO` inside a `goAsync()` window,
 * so the assertions poll rather than read straight after the call — the same
 * reason [com.ventouxlabs.bascule.service.BootReceiverTest] latches. Note that
 * the `goAsync()` call itself returns null here: a `PendingResult` exists only
 * when the framework dispatched the broadcast, which Robolectric's direct
 * `onReceive` invocation does not do. The window's *bounding* is therefore what
 * these tests can prove; that it is opened at all is a one-line read of the
 * production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScanBroadcastReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val enqueuer = FakeScaleSessionEnqueuer()
    private val alwaysClaim: (Context, String) -> Boolean = { _, _ -> true }
    private val receiver = ScanBroadcastReceiver({ enqueuer }, claimCooldown = alwaysClaim)
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun rememberDefaultUncaughtExceptionHandler() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun restoreDefaultUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    @Suppress("DEPRECATION")
    private fun scanResultIntent(vararg addresses: String): Intent {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val results = addresses.mapTo(ArrayList()) { address ->
            val remoteDevice = requireNotNull(adapter?.getRemoteDevice(address)) { "no shadow BluetoothAdapter" }
            ScanResult(remoteDevice, null, RSSI, System.nanoTime())
        }
        return Intent(ScaleScanner.ACTION_SCAN).putParcelableArrayListExtra(
            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
            results,
        )
    }

    /** Waits for the dispatch coroutine to reach [count] enqueue calls. */
    private fun awaitCalls(count: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (enqueuer.calls.size >= count) return true
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        return false
    }

    /** Gives the dispatch coroutine time to enqueue, for the assertions that it must not. */
    private fun awaitQuiet() {
        Thread.sleep(QUIET_WINDOW_MILLIS)
    }

    @Test
    fun enqueuesUniqueWorkWithKeepPolicy() {
        receiver.onReceive(context, scanResultIntent(DEVICE_ADDRESS))

        assertTrue("the advertisement was never enqueued", awaitCalls(1))
        assertEquals(DEVICE_ADDRESS, enqueuer.calls.single().address)
    }

    /**
     * WP-08 names this `secondBroadcastDuringLiveSessionIsNoOp` — that's
     * `ExistingWorkPolicy.KEEP`'s job inside a real `WorkManager`, asserted
     * on [WorkManagerScaleSessionEnqueuer] itself, not observable through a
     * fake at the receiver level. What *is* this receiver's own
     * responsibility, and what this test actually proves: with the cooldown
     * open it asks to enqueue exactly once per broadcast, carrying no
     * deduplication of its own that could suppress a genuinely new
     * advertisement. Suppression is the cooldown's job alone, asserted below.
     */
    @Test
    fun eachBroadcastAsksToEnqueueExactlyOnceWhileTheCooldownIsOpen() {
        receiver.onReceive(context, scanResultIntent(DEVICE_ADDRESS))
        receiver.onReceive(context, scanResultIntent(DEVICE_ADDRESS))

        assertTrue(awaitCalls(2))
        assertEquals(2, enqueuer.calls.size)
    }

    /**
     * pr-1-review-round3.md MEDIUM #16. `BridgeForegroundService` has had an
     * enqueue cooldown since the performance round; this path — the *primary*
     * wake path — had none, so every advertisement started a fresh GATT
     * session cycle. A manifest-declared receiver is a fresh instance per
     * broadcast, and the process may have been cold started to build it, so
     * two separate instances sharing only the on-disk window is exactly the
     * production shape.
     */
    @Test
    fun aRepeatAdvertisementSeenByAFreshReceiverInstanceIsSuppressed() {
        ScanBroadcastReceiver({ enqueuer }).onReceive(context, scanResultIntent(DEVICE_ADDRESS))
        assertTrue(awaitCalls(1))

        ScanBroadcastReceiver({ enqueuer }).onReceive(context, scanResultIntent(DEVICE_ADDRESS))
        awaitQuiet()

        assertEquals("the shared cooldown must span receiver instances", 1, enqueuer.calls.size)
    }

    /**
     * pr-1-review-round3.md HIGH #5's other half. The receiver holds its
     * `goAsync()` window until the enqueue reports itself durably recorded, so
     * a WorkManager query that never resolves must not hold the process to the
     * broadcast ANR limit. What is assertable here: a completion that never
     * arrives neither blocks the enqueue from being requested nor surfaces as
     * a crash. That the window is then closed by the timeout is not — a
     * `PendingResult` exists only under a framework dispatch (see this class's
     * KDoc), so there is nothing to observe being finished.
     */
    @Test
    fun anEnqueueThatNeverCompletesIsBoundedByTheTimeout() {
        val escaped = AtomicReference<Throwable?>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> escaped.set(error) }
        enqueuer.deferCompletion = true
        val receiver = ScanBroadcastReceiver(
            { enqueuer },
            claimCooldown = alwaysClaim,
            enqueueTimeoutMillis = 50,
        )

        receiver.onReceive(context, scanResultIntent(DEVICE_ADDRESS))

        assertTrue("the advertisement was never enqueued", awaitCalls(1))
        awaitQuiet()
        assertNull("a stuck enqueue must not surface as a crash", escaped.get())
        enqueuer.completePending()
    }

    @Test
    fun anIntentWithTheWrongActionIsIgnored() {
        val intent = Intent("some.other.action")

        receiver.onReceive(context, intent)
        awaitQuiet()

        assertTrue("no work should be enqueued for an unrelated broadcast", enqueuer.calls.isEmpty())
    }

    @Test
    fun anIntentWithNoScanResultsIsIgnored() {
        val intent = Intent(ScaleScanner.ACTION_SCAN)
            .putParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ArrayList())

        receiver.onReceive(context, intent)
        awaitQuiet()

        assertTrue("no address to act on — nothing should be enqueued", enqueuer.calls.isEmpty())
    }

    /**
     * ADR-004's 10s BroadcastReceiver window. `onReceive` must never itself
     * block: the profile-store read (which constructs an encrypted store on
     * first touch) and the cooldown's disk write both moved behind
     * `goAsync()`. Held open, the address lookup would hang `onReceive` itself
     * if it still ran inline — so reaching the assertions at all is the
     * assertion, and the JUnit timeout is what makes a regression fail rather
     * than hang.
     */
    @Test(timeout = BLOCKING_TEST_TIMEOUT_MILLIS)
    fun returnsWithinReceiverWindow() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val receiver = ScanBroadcastReceiver(
            { enqueuer },
            { entered.countDown(); release.await(); DEVICE_ADDRESS },
            alwaysClaim,
        )

        receiver.onReceive(context, scanResultIntent(DEVICE_ADDRESS))

        assertTrue("the dispatch never started", entered.await(5, TimeUnit.SECONDS))
        assertTrue("nothing may be enqueued before the address resolves", enqueuer.calls.isEmpty())
        release.countDown()
        assertTrue("the advertisement was never enqueued", awaitCalls(1))
    }

    /**
     * pr-1-review-correctness.md M13. A batched PendingIntent delivery routinely
     * carries several results and the scale's own is not necessarily first —
     * acting on `firstOrNull()` alone drops the weigh-in that triggered the scan.
     */
    @Test
    fun theScaleIsFoundWhenItIsNotFirstInTheBatch() {
        val receiver = ScanBroadcastReceiver({ enqueuer }, { DEVICE_ADDRESS }, alwaysClaim)

        receiver.onReceive(context, scanResultIntent(OTHER_ADDRESS, DEVICE_ADDRESS))

        assertTrue(awaitCalls(1))
        assertEquals(DEVICE_ADDRESS, enqueuer.calls.single().address)
    }

    @Test
    fun aBatchWithNoResultForTheActiveProfileEnqueuesNothing() {
        val receiver = ScanBroadcastReceiver({ enqueuer }, { DEVICE_ADDRESS }, alwaysClaim)

        receiver.onReceive(context, scanResultIntent(OTHER_ADDRESS))
        awaitQuiet()

        assertTrue("no advertisement from the active scale — nothing to capture", enqueuer.calls.isEmpty())
    }

    @Test
    fun anUnknownActiveAddressFallsBackToTheLeadingResult() {
        val receiver = ScanBroadcastReceiver({ enqueuer }, { null }, alwaysClaim)

        receiver.onReceive(context, scanResultIntent(OTHER_ADDRESS, DEVICE_ADDRESS))

        assertTrue(awaitCalls(1))
        assertEquals(OTHER_ADDRESS, enqueuer.calls.single().address)
    }

    private companion object {
        const val DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val OTHER_ADDRESS = "11:22:33:44:55:66"
        const val RSSI = -50
        const val POLL_INTERVAL_MILLIS = 10L
        const val QUIET_WINDOW_MILLIS = 300L
        const val BLOCKING_TEST_TIMEOUT_MILLIS = 30_000L
    }
}
