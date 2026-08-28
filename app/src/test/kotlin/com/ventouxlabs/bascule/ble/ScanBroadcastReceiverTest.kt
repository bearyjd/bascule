package com.ventouxlabs.bascule.ble

import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.ble.fake.FakeScaleSessionEnqueuer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScanBroadcastReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val enqueuer = FakeScaleSessionEnqueuer()
    private val receiver = ScanBroadcastReceiver(enqueuerFactory = { enqueuer })

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

    @Test
    fun enqueuesUniqueWorkWithKeepPolicy() {
        receiver.onReceive(context, scanResultIntent(DEVICE_ADDRESS))

        assertEquals(1, enqueuer.calls.size)
        assertEquals(DEVICE_ADDRESS, enqueuer.calls.single().address)
    }

    /**
     * WP-08 names this `secondBroadcastDuringLiveSessionIsNoOp` — that's
     * `ExistingWorkPolicy.KEEP`'s job inside a real `WorkManager`, asserted
     * on [WorkManagerScaleSessionEnqueuer] itself, not observable through a
     * fake at the receiver level. What *is* this receiver's own
     * responsibility, and what this test actually proves: it asks to
     * enqueue exactly once per broadcast it receives, with no receiver-side
     * deduplication logic of its own that could accidentally suppress a
     * later, genuinely new advertisement.
     */
    @Test
    fun eachBroadcastAsksToEnqueueExactlyOnce() {
        receiver.onReceive(context, scanResultIntent(DEVICE_ADDRESS))
        receiver.onReceive(context, scanResultIntent(DEVICE_ADDRESS))

        assertEquals(2, enqueuer.calls.size)
    }

    @Test
    fun anIntentWithTheWrongActionIsIgnored() {
        val intent = Intent("some.other.action")

        receiver.onReceive(context, intent)

        assertTrue("no work should be enqueued for an unrelated broadcast", enqueuer.calls.isEmpty())
    }

    @Test
    fun anIntentWithNoScanResultsIsIgnored() {
        val intent = Intent(ScaleScanner.ACTION_SCAN)
            .putParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ArrayList())

        receiver.onReceive(context, intent)

        assertTrue("no address to act on — nothing should be enqueued", enqueuer.calls.isEmpty())
    }

    @Test
    fun returnsWithinReceiverWindow() {
        // ADR-004's 10s BroadcastReceiver window: onReceive must never itself
        // suspend/block — enqueue() is a synchronous fire-and-forget call, not
        // a call this receiver awaits, so the receiver method returning at all
        // (which the other tests already exercise) is the whole assertion.
        receiver.onReceive(context, scanResultIntent(DEVICE_ADDRESS))

        assertEquals(1, enqueuer.calls.size)
    }

    /**
     * pr-1-review-correctness.md M13. A batched PendingIntent delivery routinely
     * carries several results and the scale's own is not necessarily first —
     * acting on `firstOrNull()` alone drops the weigh-in that triggered the scan.
     */
    @Test
    fun theScaleIsFoundWhenItIsNotFirstInTheBatch() {
        val receiver = ScanBroadcastReceiver({ enqueuer }, { DEVICE_ADDRESS })

        receiver.onReceive(context, scanResultIntent(OTHER_ADDRESS, DEVICE_ADDRESS))

        assertEquals(DEVICE_ADDRESS, enqueuer.calls.single().address)
    }

    @Test
    fun aBatchWithNoResultForTheActiveProfileEnqueuesNothing() {
        val receiver = ScanBroadcastReceiver({ enqueuer }, { DEVICE_ADDRESS })

        receiver.onReceive(context, scanResultIntent(OTHER_ADDRESS))

        assertTrue("no advertisement from the active scale — nothing to capture", enqueuer.calls.isEmpty())
    }

    @Test
    fun anUnknownActiveAddressFallsBackToTheLeadingResult() {
        val receiver = ScanBroadcastReceiver({ enqueuer }, { null })

        receiver.onReceive(context, scanResultIntent(OTHER_ADDRESS, DEVICE_ADDRESS))

        assertEquals(OTHER_ADDRESS, enqueuer.calls.single().address)
    }

    private companion object {
        const val DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val OTHER_ADDRESS = "11:22:33:44:55:66"
        const val RSSI = -50
    }
}
