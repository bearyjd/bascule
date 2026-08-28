package com.ventouxlabs.bascule.ble.session

import android.app.Application
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * pr-1-review-completeness.md C16. A [BluetoothGatt] Robolectric hands back has
 * no discovered services, so every characteristic lookup misses — which is
 * precisely the hardware/firmware-mismatch case these tests are about.
 *
 * The property under test is that the miss is *reported*, not that it carries
 * one particular sentinel: [GattSession] resolves a subscription by
 * `status == 0`, so any non-success status fails the wait immediately instead
 * of letting it run out `OPENING_WRITE_COMPLETE_TIMEOUT` and report a generic
 * "no ack" for an operation that was never attempted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AndroidGattTransportTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val adapter = requireNotNull(
        context.getSystemService(BluetoothManager::class.java)?.adapter,
    ) { "no shadow BluetoothAdapter" }

    /**
     * The shadow adapter starts disabled, and [AndroidGattTransport.connect]
     * short-circuits to `AdapterOff` without ever opening a [BluetoothGatt]
     * when it is — which would make these tests pass for the wrong reason by
     * stopping at the `gatt ?: return` guard rather than the characteristic
     * lookup they are actually about.
     */
    private fun connectedTransport(): AndroidGattTransport {
        shadowOf(adapter).setEnabled(true)
        val transport = AndroidGattTransport(context, adapter.getRemoteDevice(DEVICE_ADDRESS), adapter)
        transport.connect()
        return transport
    }

    private inline fun <reified T : TransportEvent> AndroidGattTransport.emitted(): List<T> =
        events.replayCache.filterIsInstance<T>()

    @Test
    fun writingAnAbsentCharacteristicReportsAFailureRatherThanSilence() {
        val transport = connectedTransport()

        transport.write(SigWeightProfile.USER_CONTROL_POINT, byteArrayOf(0x01))

        val event = transport.emitted<TransportEvent.WriteComplete>().single()
        assertEquals(SigWeightProfile.USER_CONTROL_POINT, event.char)
        assertNotEquals(
            "a write that was never issued must not look like a completed one",
            BluetoothGatt.GATT_SUCCESS,
            event.status,
        )
    }

    @Test
    fun subscribingToAnAbsentCharacteristicReportsAFailureRatherThanSilence() {
        val transport = connectedTransport()

        transport.enableIndications(SigWeightProfile.WEIGHT_MEASUREMENT)

        val event = transport.emitted<TransportEvent.SubscriptionEnabled>().single()
        assertEquals(SigWeightProfile.WEIGHT_MEASUREMENT, event.char)
        assertEquals(SubscriptionKind.INDICATE, event.kind)
        assertNotEquals(
            "GattSession reads status == 0 as Enabled — a missing characteristic must not pass that gate",
            BluetoothGatt.GATT_SUCCESS,
            event.status,
        )
    }

    @Test
    fun bothNoOpPathsReportTheSameCause() {
        val transport = connectedTransport()

        transport.write(SigWeightProfile.USER_CONTROL_POINT, byteArrayOf(0x01))
        transport.enableNotifications(UNKNOWN_CHARACTERISTIC)

        val write = transport.emitted<TransportEvent.WriteComplete>().single()
        val subscription = transport.emitted<TransportEvent.SubscriptionEnabled>().single()
        assertEquals(
            "both no-op paths report the same cause, so a log reads the same either way",
            write.status,
            subscription.status,
        )
        assertEquals(SubscriptionKind.NOTIFY, subscription.kind)
    }

    /**
     * pr-1-review-round3 HIGH #1. Every `GattSession` retry branch is supposed
     * to `close()` before looping back to `connect()`, and the contention
     * branch did not — which orphaned the previous `BluetoothGatt` client
     * registration. The per-app client table is finite, so repeated leaks
     * surface much later as permanent status-133 until the process is killed,
     * pointing at nothing. This guard makes that class of bug unreachable from
     * here regardless of which branch calls in.
     */
    @Test
    fun connectingAgainClosesThePriorGattClient() {
        shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice(DEVICE_ADDRESS)
        val transport = AndroidGattTransport(context, device, adapter)

        transport.connect()
        val first = shadowOf(device).bluetoothGatts.single()
        transport.connect()

        assertTrue(
            "the previous BluetoothGatt client must be closed, not orphaned",
            shadowOf(first).isClosed,
        )
        assertEquals("the retry must still get a fresh client", 2, shadowOf(device).bluetoothGatts.size)
    }

    private companion object {
        const val DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF"
        val UNKNOWN_CHARACTERISTIC: UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
    }
}
