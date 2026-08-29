package com.ventouxlabs.bascule.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun rememberDefaultUncaughtExceptionHandler() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun restoreDefaultUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    @Test
    fun onReceiveCallsArmAndWaitsForItToFinish() {
        val latch = CountDownLatch(1)
        val receiver = BootReceiver(arm = { latch.countDown(); true })

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue("arm() was never called", latch.await(5, TimeUnit.SECONDS))
    }

    /**
     * C11: a scan permission revoked across a reboot — or a corrupt DataStore
     * file, or a keystore fault behind the real `arm()` — makes it throw. The
     * throwable must be recorded and contained: reaching the thread's default
     * uncaught handler from a `BOOT_COMPLETED` broadcast kills the process on
     * *every* boot, and an unarmed scan is the far cheaper failure.
     */
    @Test
    fun anArmThatThrowsIsRecordedAndDoesNotReachTheUncaughtExceptionHandler() {
        val escaped = AtomicReference<Throwable?>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> escaped.set(error) }
        val recorded = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        val receiver = BootReceiver(
            arm = { throw SecurityException("scan permission revoked") },
            onFailure = { _, error -> recorded.set(error); latch.countDown() },
        )

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue("a failed arm at boot must be recorded", latch.await(5, TimeUnit.SECONDS))
        // Not assertSame: kotlinx-coroutines' stack-trace recovery hands the
        // handler a copy of the original across the suspension boundary.
        assertTrue(recorded.get() is SecurityException)
        assertEquals("scan permission revoked", recorded.get()?.message)
        assertNull("a failed arm at boot must not crash the process", escaped.get())
    }

    /**
     * The receiver is exported (`BOOT_COMPLETED` requires it), so any installed
     * app can send it an explicit intent with an arbitrary action. Only the
     * declared boot action may trigger a re-arm.
     */
    @Test
    fun anIntentWithAnotherActionDoesNotArm() {
        val armed = CountDownLatch(1)
        val receiver = BootReceiver(arm = { armed.countDown(); true })

        receiver.onReceive(context, Intent("com.attacker.FORCE_REARM"))

        assertFalse(
            "only ACTION_BOOT_COMPLETED may re-arm the scan",
            armed.await(1, TimeUnit.SECONDS),
        )
    }

    /**
     * A DataStore read that never returns must not hold the `goAsync()` window
     * open until the broadcast ANR limit; the timeout cancels `arm()` and the
     * receiver finishes normally rather than crashing.
     */
    @Test
    fun anArmThatNeverReturnsIsBoundedByTheTimeout() {
        val escaped = AtomicReference<Throwable?>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> escaped.set(error) }
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val receiver = BootReceiver(
            arm = {
                started.countDown()
                try {
                    awaitCancellation()
                } finally {
                    cancelled.countDown()
                }
            },
            armTimeoutMillis = 50,
        )

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue("arm() was never called", started.await(5, TimeUnit.SECONDS))
        assertTrue("a stuck arm() must be cancelled by the timeout", cancelled.await(15, TimeUnit.SECONDS))
        assertNull("the timeout must not surface as a crash", escaped.get())
    }
}
