package com.ventouxlabs.bascule.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The re-arm itself (`ScaleScanner.arm()`'s gating, the bridge restart) belongs
 * to those classes' own tests; this proves only the trigger — which state
 * transition reaches the re-arm and which are ignored. Same `CountDownLatch`
 * shape as [BootReceiverTest] and for the same reason: the production path
 * launches on `Dispatchers.IO`, which no virtual clock controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AdapterStateReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun stateIntent(state: Int) =
        Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state)

    private fun receiverRecording(latch: CountDownLatch) =
        AdapterStateReceiver(rearm = { latch.countDown() }, onFailure = { _, _ -> })

    @Test
    fun adapterTurningOnRearmsTheScan() {
        val latch = CountDownLatch(1)

        receiverRecording(latch).onReceive(context, stateIntent(BluetoothAdapter.STATE_ON))

        assertTrue(
            "a scan registration does not survive the Bluetooth stack restarting",
            latch.await(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun adapterTurningOffDoesNotRearm() {
        val latch = CountDownLatch(1)

        receiverRecording(latch).onReceive(context, stateIntent(BluetoothAdapter.STATE_OFF))

        assertFalse("there is no stack to register against", latch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun adapterStillTurningOnDoesNotRearm() {
        val latch = CountDownLatch(1)

        receiverRecording(latch).onReceive(context, stateIntent(BluetoothAdapter.STATE_TURNING_ON))

        assertFalse(
            "registering before the stack is up recreates the silent-registration bug",
            latch.await(1, TimeUnit.SECONDS),
        )
    }

    @Test
    fun anUnrelatedActionIsIgnored() {
        val latch = CountDownLatch(1)

        receiverRecording(latch).onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertFalse(latch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun aStateChangeCarryingNoStateExtraIsIgnored() {
        val latch = CountDownLatch(1)

        receiverRecording(latch).onReceive(context, Intent(BluetoothAdapter.ACTION_STATE_CHANGED))

        assertFalse("a missing EXTRA_STATE must not read as STATE_ON", latch.await(1, TimeUnit.SECONDS))
    }
}
