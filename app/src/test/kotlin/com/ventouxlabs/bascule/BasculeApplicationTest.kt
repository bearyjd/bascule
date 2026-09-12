package com.ventouxlabs.bascule

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.service.BridgeForegroundService
import com.ventouxlabs.bascule.service.BridgeOwner
import com.ventouxlabs.bascule.service.BridgeOwnership
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

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /**
     * Stands in for the live service, faithfully enough that the controller's
     * guards are exercised for the right reason: it acquires on a start the way
     * `onStartCommand` does, and refuses to release a claim it does not hold.
     *
     * Replaces a process-wide `BridgeForegroundService.isRunning` flag that had
     * to be reset in an `@After` or it leaked into every later test in the JVM.
     */
    private class FakeOwnership(initial: Set<BridgeOwner> = emptySet()) : BridgeOwnership {
        override var owners: Set<BridgeOwner> = initial
            private set

        fun acquireAsServiceWould(intent: Intent) {
            if (intent.getBooleanExtra(BridgeForegroundService.EXTRA_REARM_SCAN, false)) return
            owners = owners + if (intent.getLongExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, 0L) > 0L) {
                BridgeOwner.WEIGH_NOW
            } else {
                BridgeOwner.ALWAYS_ON
            }
        }

        override fun releaseOwner(owner: BridgeOwner) {
            if (owner !in owners) return
            owners = owners - owner
        }
    }

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
     * `onDestroy` is asynchronous, so an instance stays published for a while
     * after the last claim is released. An adapter-on broadcast landing in that
     * gap would otherwise `startForegroundService` a bridge nobody owns, keeping
     * it alive after the user switched always-on off.
     *
     * The fixture deliberately leaves a live instance published and lets the
     * start acquire a real claim, so the condition under test is "no owner
     * wants it" rather than "no instance exists". An earlier version of this
     * test passed with the guard deleted precisely because the fixture failed
     * the *first* condition of the guard and never reached the second.
     */
    @Test
    fun rearmAfterTheLastOwnerReleasesDoesNotResurrectTheBridge() {
        val started = mutableListOf<Intent>()
        val ownership = FakeOwnership()
        val controller = AndroidBridgeServiceController(
            context = context,
            onStartResult = {},
            starter = { started += it; ownership.acquireAsServiceWould(it) },
            ownership = { ownership },
        )

        controller.start()
        controller.stop()
        controller.rearmScan()

        assertTrue("the always-on claim was given up", ownership.owners.isEmpty())
        assertEquals("only the original start; the re-arm must not fire", 1, started.size)
    }

    /**
     * The always-on toggle going off must not end a "Weigh now" window the user
     * started separately — the H-2 failure, now structural rather than
     * defended by a config read in `ScaleViewModel.cancelWeighNow`.
     */
    @Test
    fun stoppingAlwaysOnLeavesAWeighNowWindowHoldingTheBridge() {
        val started = mutableListOf<Intent>()
        val ownership = FakeOwnership()
        val controller = AndroidBridgeServiceController(
            context = context,
            onStartResult = {},
            starter = { started += it; ownership.acquireAsServiceWould(it) },
            ownership = { ownership },
        )

        controller.start()
        controller.startBounded(90_000L)
        controller.stop()

        assertEquals("the window still wants the bridge", setOf(BridgeOwner.WEIGH_NOW), ownership.owners)
        controller.rearmScan()
        assertEquals("and so a re-arm is still legitimate", 3, started.size)
    }

    /** A claim taken again after being released re-enables the re-arm. */
    @Test
    fun rearmWorksAgainOnceAnOwnerReacquires() {
        val started = mutableListOf<Intent>()
        val ownership = FakeOwnership()
        val controller = AndroidBridgeServiceController(
            context = context,
            onStartResult = {},
            starter = { started += it; ownership.acquireAsServiceWould(it) },
            ownership = { ownership },
        )

        controller.start()
        controller.stop()
        controller.start()
        controller.rearmScan()

        assertEquals("re-acquiring makes the re-arm fire again", 3, started.size)
    }

    /**
     * With no instance there is nothing holding a claim, but a start may still
     * be in flight, so the context stop is the only lever available. This is the
     * one branch that is not an owner decision.
     */
    @Test
    fun stopWithNoLiveServiceFallsBackToTheContextStop() {
        val started = mutableListOf<Intent>()
        val controller = AndroidBridgeServiceController(
            context = context,
            onStartResult = {},
            starter = { started += it },
            ownership = { null },
        )

        controller.start()
        controller.stop()
        controller.rearmScan()

        assertEquals("no instance means no re-arm either", 1, started.size)
    }

    /** Cancelling a window that was never started must not reach for the context stop. */
    @Test
    fun cancelBoundedWithNoWindowRunningIsANoOp() {
        val ownership = FakeOwnership(setOf(BridgeOwner.ALWAYS_ON))
        val controller = AndroidBridgeServiceController(
            context = context,
            onStartResult = {},
            starter = {},
            ownership = { ownership },
        )

        controller.cancelBounded()

        assertEquals(setOf(BridgeOwner.ALWAYS_ON), ownership.owners)
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
