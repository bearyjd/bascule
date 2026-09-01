package com.ventouxlabs.bascule.service

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.ble.fake.FakeScaleSessionEnqueuer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowService

/**
 * S4: the permission gate has to run *before* `startForeground`, because on API
 * 34+ a `connectedDevice` foreground service may only start while the app holds
 * a Bluetooth runtime permission — checking afterwards means the
 * `SecurityException` has already been thrown out of `onCreate`.
 *
 * Only the denied path is covered. The granted path runs on into
 * `startActiveScan`, which casts to the real `BasculeApplication` and would
 * need a live Keystore for its encrypted stores — red for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BridgeForegroundServiceTest {

    @Test
    fun onCreateWithoutScanPermissionStopsInsteadOfGoingForeground() {
        val controller = Robolectric.buildService(BridgeForegroundService::class.java)

        controller.create()

        val shadow: ShadowService = shadowOf(controller.get())
        assertNull("startForeground must not run before the permission check", shadow.lastForegroundNotification)
        assertTrue("the service must stop itself rather than idle", shadow.isStoppedBySelf)
    }

    /**
     * pr-1-review-patterns.md P20. The dispatch path this service owns is now
     * reachable from a test, the way `ScanBroadcastReceiver`'s already was:
     * substituting [enqueuerFactory] keeps a real `WorkManager` out of the JVM
     * lane. The service is built but never `create()`d — `onCreate` runs the
     * permission gate and `startActiveScan`'s cast to the real
     * `BasculeApplication`, neither of which this behavior depends on.
     */
    @Test
    fun aScanResultIsDispatchedThroughTheInjectedEnqueuer() {
        val enqueuer = FakeScaleSessionEnqueuer()
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.enqueuerFactory = { enqueuer }

        service.enqueueOnce(scanResult(DEVICE_ADDRESS))

        assertEquals(DEVICE_ADDRESS, enqueuer.calls.single().address)
    }

    /**
     * The cooldown is what keeps a scale advertising several times a second
     * from starting a fresh GATT session per advertisement; with the enqueuer
     * injectable, that gate is finally observable at the service level.
     */
    @Test
    fun aRepeatAdvertisementInsideTheCooldownIsNotDispatchedAgain() {
        val enqueuer = FakeScaleSessionEnqueuer()
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.enqueuerFactory = { enqueuer }

        service.enqueueOnce(scanResult(DEVICE_ADDRESS))
        service.enqueueOnce(scanResult(DEVICE_ADDRESS))

        assertEquals(1, enqueuer.calls.size)
    }

    /**
     * pr-1-review-round3.md MEDIUM #16's other half: the window now lives in
     * SharedPreferences so both wake paths share it. Deliberate behavior
     * change for this service — a sighting the broadcast path already claimed
     * no longer starts a second session here.
     */
    @Test
    fun aSightingAlreadyClaimedByTheBroadcastPathIsNotDispatchedAgain() {
        val enqueuer = FakeScaleSessionEnqueuer()
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(ScanEnqueueCooldown(context).claim(DEVICE_ADDRESS))
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.enqueuerFactory = { enqueuer }

        service.enqueueOnce(scanResult(DEVICE_ADDRESS))

        assertTrue("both wake paths share one window per address", enqueuer.calls.isEmpty())
    }

    /**
     * pr-1-review-round3.md MEDIUM #15. `startActiveScan` used to return early
     * with no `stopSelf`, leaving a foreground service running forever behind
     * an undismissable "bridging" notification with no scan under it.
     */
    @Test
    fun noActiveProfileStopsTheServiceInsteadOfIdlingForeground() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { null }

        controller.create()

        assertTrue("a bridge with nothing to scan for must stop", shadowOf(controller.get()).isStoppedBySelf)
    }

    /**
     * The same inert state reached by the other route: a stored profile can
     * carry a malformed address, which `setDeviceAddress` rejects, and a
     * permission revoked mid-flight (E13) throws `SecurityException` out of
     * `startScan`. The bare `runCatching` swallowed both.
     */
    @Test
    fun aScanThatCannotBeStartedStopsTheServiceInsteadOfIdlingForeground() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { "not-a-mac-address" }

        controller.create()

        assertTrue("a bridge whose scan never started must stop", shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun aScanThatStartsLeavesTheServiceRunningInForeground() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { DEVICE_ADDRESS }

        controller.create()

        val shadow = shadowOf(controller.get())
        assertNotNull("the bridge notification is owed once a scan is running", shadow.lastForegroundNotification)
        assertFalse("a running scan must not stop the service", shadow.isStoppedBySelf)
    }

    /**
     * S4 continued: `weighNow()`'s bounded scan reuses this same service, so a
     * start carrying the duration extra must arm a self-stop — otherwise the
     * only difference between "weigh now" and "always-on" would be a duration
     * nobody enforces. [BridgeForegroundService.boundStopScheduler] is
     * injectable for the same reason [enqueuerFactory] and
     * [activeAddressProvider] are: real time cannot be waited out in a JVM test.
     */
    @Test
    fun aBoundedStartArmsASelfStopAfterItsWindow() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { DEVICE_ADDRESS }
        var scheduledMillis: Long? = null
        controller.get().boundStopScheduler = { millis, onExpire -> scheduledMillis = millis; onExpire() }
        controller.create()

        controller.get().onStartCommand(
            Intent().putExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, BOUND_MILLIS),
            0,
            1,
        )

        assertEquals(BOUND_MILLIS, scheduledMillis)
        assertTrue("the bounded window elapsing must stop the service", shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun aPlainStartNeverArmsASelfStop() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { DEVICE_ADDRESS }
        var scheduled = false
        controller.get().boundStopScheduler = { _, _ -> scheduled = true }
        controller.create()

        controller.get().onStartCommand(Intent(), 0, 1)

        assertFalse("the always-on toggle's start must not carry a self-stop timer", scheduled)
        assertFalse("no scheduled stop means the service is still running", shadowOf(controller.get()).isStoppedBySelf)
    }

    private fun grantedController(): ServiceController<BridgeForegroundService> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        return Robolectric.buildService(BridgeForegroundService::class.java)
    }

    @Suppress("DEPRECATION")
    private fun scanResult(address: String): ScanResult {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = requireNotNull(adapter?.getRemoteDevice(address)) { "no shadow BluetoothAdapter" }
        return ScanResult(device, null, RSSI, System.nanoTime())
    }

    private companion object {
        const val DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val RSSI = -50
        const val BOUND_MILLIS = 120_000L
    }
}
