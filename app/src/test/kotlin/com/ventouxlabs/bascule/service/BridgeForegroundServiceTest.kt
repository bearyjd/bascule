package com.ventouxlabs.bascule.service

import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.ble.fake.FakeScaleSessionEnqueuer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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

        val shadow: ShadowService = org.robolectric.Shadows.shadowOf(controller.get())
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
    }
}
