package com.ventouxlabs.bascule.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `arm()`'s own gating (disabled automatic capture, no active profile) is
 * [com.ventouxlabs.bascule.ble.ScaleScannerTest]'s responsibility, not
 * re-tested here — this only proves `onReceive` calls it at all. Uses a
 * `CountDownLatch` because the real production path launches on
 * `Dispatchers.IO`, a real background dispatcher `runTest`'s virtual clock
 * does not control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun onReceiveCallsArmAndWaitsForItToFinish() {
        val latch = CountDownLatch(1)
        val receiver = BootReceiver(arm = { latch.countDown(); true })

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue("arm() was never called", latch.await(5, TimeUnit.SECONDS))
    }
}
