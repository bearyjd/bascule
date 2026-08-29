package com.ventouxlabs.bascule.delivery

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
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
}
