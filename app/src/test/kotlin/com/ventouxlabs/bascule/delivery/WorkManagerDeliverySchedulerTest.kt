package com.ventouxlabs.bascule.delivery

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Regression (round-3 C1). Two independently-unique work names let a periodic
 * drain and a triggered drain run [DeliveryWorker] concurrently; both selected
 * the same PENDING rows and both submitted them, and v1 sends no idempotency key
 * for the server to dedupe on.
 *
 * Only enqueue bookkeeping is asserted — the work is never executed, because
 * [DeliveryWorker.doWork] casts to `BasculeApplication`, which is not
 * constructible here (see [com.ventouxlabs.bascule.ble.session.ScaleSessionWorkerTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WorkManagerDeliverySchedulerTest {

    private lateinit var manager: WorkManager
    private lateinit var scheduler: WorkManagerDeliveryScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        manager = WorkManager.getInstance(context)
        scheduler = WorkManagerDeliveryScheduler(context)
    }

    private fun drainWork(): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(DeliveryWorker.UNIQUE_WORK_NAME).get()

    private fun periodicWork(): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(DeliveryPeriodicKickWorker.PERIODIC_WORK_NAME).get()

    private fun retryKickWork(): List<WorkInfo> =
        manager.getWorkInfosForUniqueWork(DeliveryPeriodicKickWorker.RETRY_KICK_WORK_NAME).get()

    @Test
    fun aSecondTriggerKeepsTheDrainAlreadyInFlightInsteadOfStartingASecondOne() {
        scheduler.triggerImmediateDrain()
        scheduler.triggerImmediateDrain()

        assertEquals(1, drainWork().size)
    }

    /**
     * The C1 assertion. The periodic schedule must not own a drain of its own:
     * it registers a kick worker under its own name, and the only thing that
     * ever runs [DeliveryWorker] is the single `delivery-drain` unique work.
     */
    @Test
    fun thePeriodicScheduleRunsAKickWorkerAndNotASecondConcurrentDrain() {
        scheduler.ensurePeriodicDrain()

        // WorkManager tags every request with its worker's class name, which is
        // the only way from here to see *which* worker a schedule would run.
        val tags = periodicWork().single().tags
        assertTrue(
            "the periodic schedule must run the kick worker, not a second DeliveryWorker",
            DeliveryPeriodicKickWorker::class.java.name in tags,
        )
        assertTrue(
            "a periodic DeliveryWorker is exactly the drain that could run concurrently with a triggered one",
            DeliveryWorker::class.java.name !in tags,
        )
    }

    /**
     * The other half of C1: the periodic name must not swallow immediate
     * triggers either. Sharing one name between the periodic request and the
     * triggers would do exactly that — periodic work never reaches a finished
     * state, so `KEEP` against it drops every trigger, forever.
     */
    @Test
    fun anImmediateTriggerStillGetsItsDrainWhileThePeriodicScheduleIsRegistered() {
        scheduler.ensurePeriodicDrain()

        scheduler.triggerImmediateDrain()

        assertEquals(1, drainWork().size)
    }

    /**
     * Regression (round-3 HIGH #3). The continuation is enqueued *by* the drain
     * it would be deduped against, so it cannot use [DeliveryScheduler.triggerImmediateDrain]'s
     * KEEP policy — that would silently drop it and stall pagination until the
     * 15-minute periodic net.
     */
    @Test
    fun aContinuationIsQueuedBehindTheRunningDrainRatherThanDroppedByIt() {
        scheduler.triggerImmediateDrain()

        scheduler.enqueueContinuation()

        assertEquals(2, drainWork().size)
    }

    @Test
    fun ensurePeriodicDrainIsIdempotent() {
        scheduler.ensurePeriodicDrain()
        scheduler.ensurePeriodicDrain()

        assertEquals(1, periodicWork().size)
    }

    /**
     * Retry pacing after a failed drain. The kick is a delayed request, and a
     * delayed request must live under its own name: under `delivery-drain` it
     * would be exactly the ENQUEUED work that [DeliveryScheduler.triggerImmediateDrain]'s
     * KEEP dedupes every trigger against.
     */
    @Test
    fun aRetryKickIsADelayedKickWorkerUnderItsOwnNameWithTheNetworkConstraint() {
        scheduler.scheduleRetryKick(delayMillis = 45_000L)

        val kick = retryKickWork().single()
        assertEquals(45_000L, kick.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, kick.constraints.requiredNetworkType)
        assertTrue(
            "the kick must run the kick worker, which asks for the one unique drain rather than draining itself",
            DeliveryPeriodicKickWorker::class.java.name in kick.tags,
        )
    }

    /**
     * REPLACE, not KEEP: each failed drain recomputes the delay from the rows'
     * `nextAttemptMillis`, so the latest one is the one that knows when the
     * soonest waiting row is due. A kept stale kick would fire at the wrong time.
     */
    @Test
    fun aLaterRetryKickReplacesAnEarlierOneRatherThanBeingDroppedByIt() {
        scheduler.scheduleRetryKick(delayMillis = 1_000L)

        scheduler.scheduleRetryKick(delayMillis = 5_000L)

        val kick = retryKickWork().single()
        assertEquals("the newer delay wins; KEEP would have left the 1 s one in place", 5_000L, kick.initialDelayMillis)
    }

    /**
     * The defect this schedule exists to fix. A `Result.retry()` from the drain
     * parked a delayed request under `delivery-drain` in WorkManager's own
     * backoff (30 s doubling to a 5-hour cap), and KEEP dropped every immediate
     * trigger — a new capture, a saved token, even the periodic kick — until
     * that backoff elapsed. A kick under its own name leaves the drain name
     * free, so a trigger during the wait still gets its drain.
     *
     * The tag assertion is what makes this discriminate: a kick enqueued under
     * the drain name would itself satisfy a "one request under `delivery-drain`"
     * check while the real drain had been dropped.
     */
    @Test
    fun aPendingRetryKickDoesNotStopAnImmediateTriggerFromEnqueuingADrain() {
        scheduler.scheduleRetryKick(delayMillis = 60_000L)

        scheduler.triggerImmediateDrain()

        val tags = drainWork().single().tags
        assertTrue(
            "the drain name must hold a DeliveryWorker, not the kick that would have blocked it",
            DeliveryWorker::class.java.name in tags,
        )
        assertTrue(
            "the kick belongs under its own name so that it can never be what KEEP collides with",
            DeliveryPeriodicKickWorker::class.java.name !in tags,
        )
    }
}
