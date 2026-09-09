package com.ventouxlabs.bascule

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.service.BridgeForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Devil's-advocate review, correctness round 1: the boot-time foreground-service
 * start had a `ForegroundServiceStartNotAllowedException` guard; the interactive
 * toggle (`ScaleViewModel.setAlwaysOnBridging` -> `bridgeService.start()`) shared
 * the same underlying Android call with none, so an uncaught throw there could
 * crash the process from a plain UI tap. Both paths now share one
 * [AndroidBridgeServiceController] instance, so this test covers both.
 *
 * [AndroidBridgeServiceController.starter] is injected because Robolectric's
 * shadow of `startForegroundService` does not throw
 * `ForegroundServiceStartNotAllowedException` — there is no way to provoke the
 * real exception in this test lane, so the exception-handling logic is tested
 * directly against a substitute that does throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BasculeApplicationTest {

    /** Process-wide state, so it has to be reset or it leaks into every later test in the JVM. */
    @After
    fun clearServiceRunningFlag() {
        BridgeForegroundService.isRunning = false
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun aRefusedStartIsCaughtAndReportedRatherThanThrown() {
        var reportedSucceeded: Boolean? = null
        val controller = AndroidBridgeServiceController(
            context = context,
            onStartResult = { reportedSucceeded = it },
            starter = { throw IllegalStateException("app not in a state that may start a foreground service") },
        )

        controller.start() // must not throw

        assertFalse("a refused start must be reported as failed, not silently treated as success", reportedSucceeded!!)
    }

    @Test
    fun aSuccessfulStartIsReportedAsSuch() {
        var reportedSucceeded: Boolean? = null
        val controller = AndroidBridgeServiceController(
            context = context,
            onStartResult = { reportedSucceeded = it },
            starter = {},
        )

        controller.start()

        assertTrue(reportedSucceeded!!)
    }

    @Test(expected = SecurityException::class)
    fun anUnrelatedExceptionIsNotSwallowed() {
        val controller = AndroidBridgeServiceController(
            context = context,
            onStartResult = {},
            starter = { throw SecurityException("unrelated to the foreground-service restriction") },
        )

        controller.start()
    }

    /**
     * `Context.stopService` is asynchronous, so `BridgeForegroundService.isRunning`
     * stays true until `onDestroy`. An adapter-on broadcast landing in that gap
     * would otherwise start the service again, keeping a bridge alive after the
     * user switched always-on off. The guard is intent, not observed liveness.
     */
    @Test
    fun rearmAfterAStopRequestDoesNotResurrectTheBridge() {
        val started = mutableListOf<Intent>()
        val controller = AndroidBridgeServiceController(
            context = ApplicationProvider.getApplicationContext(),
            onStartResult = {},
            starter = { started += it },
        )
        // The whole point of the guard: stopService is asynchronous, so the
        // service is still live here. Leaving this false made an earlier
        // version of this test pass with the guard deleted.
        BridgeForegroundService.isRunning = true

        controller.start()
        controller.stop()
        controller.rearmScan()

        assertEquals("only the original start; the re-arm must not fire", 1, started.size)
    }

    /** A start after a stop clears the guard: the user turning it back on must re-arm normally. */
    @Test
    fun rearmWorksAgainOnceTheServiceIsStartedAfterAStop() {
        val started = mutableListOf<Intent>()
        val controller = AndroidBridgeServiceController(
            context = ApplicationProvider.getApplicationContext(),
            onStartResult = {},
            starter = { started += it },
        )
        BridgeForegroundService.isRunning = true

        controller.start()
        controller.stop()
        controller.start()
        controller.rearmScan()

        assertEquals("start clears the guard, so the re-arm fires again", 3, started.size)
    }

    /** `startBounded` shares [AndroidBridgeServiceController.start]'s exception handling — only the intent differs. */
    @Test
    fun startBoundedCarriesTheDurationAsAnIntentExtra() {
        var captured: Intent? = null
        val controller = AndroidBridgeServiceController(
            context = context,
            onStartResult = {},
            starter = { intent -> captured = intent },
        )

        controller.startBounded(90_000L)

        assertEquals(90_000L, captured?.getLongExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, -1L))
    }

    @Test
    fun startDoesNotCarryTheBoundedExtra() {
        var captured: Intent? = null
        val controller = AndroidBridgeServiceController(
            context = context,
            onStartResult = {},
            starter = { intent -> captured = intent },
        )

        controller.start()

        assertEquals(0L, captured?.getLongExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, 0L))
    }
}
