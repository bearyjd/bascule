package com.ventouxlabs.bascule.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertNotNull
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
     * C11: a scan permission revoked across a reboot makes the real `arm()`
     * throw. `onReceive` launches on a bare `CoroutineScope(Dispatchers.IO)`
     * with no `CoroutineExceptionHandler` and catches nothing, so the throwable
     * reaches the thread's default uncaught handler — from a `BOOT_COMPLETED`
     * broadcast. Pinned as current behaviour: neither swallowed nor handled.
     *
     * What this pins is that the failure escapes at all. Its *identity* is not
     * assertable in this lane: `goAsync()` returns a `PendingResult` only when
     * the framework put one there, and neither a direct `onReceive` nor
     * Robolectric's own `sendBroadcast` does, so the `finally`'s
     * `pending.finish()` raises a `NullPointerException` that replaces the
     * `SecurityException` before it reaches the handler. Asserting the type
     * would pin that harness artifact rather than the production path, and
     * needs the instrumented lane (Task 6) instead.
     */
    @Test
    fun anArmThatThrowsIsNotSwallowedAndReachesTheUncaughtExceptionHandler() {
        val thrown = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            thrown.set(error)
            latch.countDown()
        }
        val receiver = BootReceiver(arm = { throw SecurityException("scan permission revoked") })

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue(
            "a failed arm at boot must not be silently swallowed inside the receiver's coroutine",
            latch.await(5, TimeUnit.SECONDS),
        )
        assertNotNull(thrown.get())
    }
}
