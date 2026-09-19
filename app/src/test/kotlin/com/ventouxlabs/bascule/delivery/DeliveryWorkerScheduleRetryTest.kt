package com.ventouxlabs.bascule.delivery

import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.delivery.fake.FakeDeliveryScheduler
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.readingFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step between the drain and the worker's result: what
 * [DeliveryWorker.scheduleRetryIfNeeded] reads from the rows and asks of the
 * scheduler. Run through the fakes because `doWork` itself casts its context
 * to `BasculeApplication`, which is not constructible in this JUnit lane, and
 * an untested glue step is one whose whole body can be deleted without a
 * single test noticing.
 */
class DeliveryWorkerScheduleRetryTest {

    private val dao = FakeReadingDao()
    private val scheduler = FakeDeliveryScheduler()

    @Test
    fun aFailedDrainWithAWaitingRowKicksWhenThatRowBecomesDue() = runTest {
        dao.insert(readingFixture(id = "waiting", nextAttemptMillis = 40_000L))

        DeliveryWorker.scheduleRetryIfNeeded(DrainOutcome.FAILED, dao, scheduler, nowMillis = 10_000L)

        assertEquals(listOf(30_000L), scheduler.retryKickDelays)
        assertEquals(0, scheduler.continuations)
    }

    /** A `Retry-After: 0` leaves its row due with nothing in the future; the base delay is the floor. */
    @Test
    fun aFailedDrainWithOnlyADueRowKicksAfterTheLadderBase() = runTest {
        dao.insert(readingFixture(id = "due-again", nextAttemptMillis = 10_000L))

        DeliveryWorker.scheduleRetryIfNeeded(DrainOutcome.FAILED, dao, scheduler, nowMillis = 10_000L)

        assertEquals(listOf(DeliveryCoordinator.BACKOFF_BASE_MILLIS), scheduler.retryKickDelays)
    }

    @Test
    fun aFailedDrainWithNoPendingRowsSchedulesNothing() = runTest {
        dao.insert(readingFixture(id = "already-sent", status = ReadingStatus.SENT))

        DeliveryWorker.scheduleRetryIfNeeded(DrainOutcome.FAILED, dao, scheduler, nowMillis = 10_000L)

        assertTrue(scheduler.retryKickDelays.isEmpty())
        assertEquals(0, scheduler.continuations)
    }

    @Test
    fun morePagesEnqueuesOneContinuationAndNoKick() = runTest {
        dao.insert(readingFixture(id = "next-page"))

        DeliveryWorker.scheduleRetryIfNeeded(DrainOutcome.MORE_PAGES, dao, scheduler, nowMillis = 10_000L)

        assertEquals(1, scheduler.continuations)
        assertTrue(scheduler.retryKickDelays.isEmpty())
    }

    @Test
    fun aFinishedDrainAsksForNothingEvenWithARowStillWaiting() = runTest {
        dao.insert(readingFixture(id = "waiting", nextAttemptMillis = 40_000L))

        DeliveryWorker.scheduleRetryIfNeeded(DrainOutcome.DONE, dao, scheduler, nowMillis = 10_000L)

        assertTrue(scheduler.retryKickDelays.isEmpty())
        assertEquals(0, scheduler.continuations)
        assertEquals(0, scheduler.immediateDrains)
    }
}
