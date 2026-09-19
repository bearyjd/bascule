package com.ventouxlabs.bascule.delivery

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression (round-3 HIGH #3). Pagination and failure both used to map to
 * `Result.retry()`, so WorkManager's exponential ladder was applied to a healthy
 * multi-page drain — a `BLOCKED_AUTH` recovery of several hundred rows took
 * hours instead of minutes.
 *
 * Then the failure case was retired from `retry()` too: a retrying drain sits
 * under the drain name in WorkManager's own backoff (30 s doubling to a 5-hour
 * cap), and `triggerImmediateDrain`'s KEEP drops every trigger — a new capture,
 * a saved token, even the 15-minute periodic kick — for as long as it sits
 * there. Retry pacing is now a delayed kick under its own name, timed from the
 * rows' §3.4 `nextAttemptMillis`; [DeliveryWorker.retryKickDelayFor] decides it.
 *
 * Only the pure decisions are covered here. `doWork` itself casts its context
 * to `BasculeApplication`, which is not constructible in this project's JUnit
 * lane, so the step that reads the rows and calls the scheduler is exercised
 * through [DeliveryWorker.scheduleRetryIfNeeded] in [DeliveryWorkerScheduleRetryTest],
 * and the kick's WorkManager bookkeeping in [WorkManagerDeliverySchedulerTest].
 */
class DeliveryWorkerResultTest {

    @Test
    fun aFinishedDrainSucceeds() {
        assertEquals(ListenableWorker.Result.success(), DeliveryWorker.resultFor(DrainOutcome.DONE))
    }

    @Test
    fun morePagesSucceedsSoTheContinuationRunsWithoutWorkManagersBackoff() {
        assertEquals(ListenableWorker.Result.success(), DeliveryWorker.resultFor(DrainOutcome.MORE_PAGES))
    }

    /**
     * A `retry()` here would park a delayed request under `delivery-drain`, and
     * that is exactly what `ExistingWorkPolicy.KEEP` collides with: every
     * immediate trigger is dropped until WorkManager's backoff — up to five
     * hours — elapses. The row ladder is the only retry pacing.
     */
    @Test
    fun aFailedDrainSucceedsBecauseADelayedDrainWouldBlockEveryTrigger() {
        assertEquals(ListenableWorker.Result.success(), DeliveryWorker.resultFor(DrainOutcome.FAILED))
    }

    @Test
    fun aFailedDrainKicksExactlyWhenTheSoonestWaitingRowBecomesDue() {
        assertEquals(
            25_000L,
            DeliveryWorker.retryKickDelayFor(
                outcome = DrainOutcome.FAILED,
                earliestFutureAttemptMillis = 35_000L,
                anyRowDue = false,
                nowMillis = 10_000L,
            ),
        )
    }

    /**
     * The DAO excludes rows that are already due, but the clock keeps running
     * between that query and this call; a row that became due in the gap gets
     * an immediate kick, never a negative delay WorkManager would reject.
     */
    @Test
    fun aRowThatBecameDueSinceTheQueryKicksImmediately() {
        assertEquals(
            0L,
            DeliveryWorker.retryKickDelayFor(
                outcome = DrainOutcome.FAILED,
                earliestFutureAttemptMillis = 10_000L,
                anyRowDue = false,
                nowMillis = 10_000L,
            ),
        )
        assertEquals(
            0L,
            DeliveryWorker.retryKickDelayFor(
                outcome = DrainOutcome.FAILED,
                earliestFutureAttemptMillis = 9_000L,
                anyRowDue = false,
                nowMillis = 10_000L,
            ),
        )
    }

    @Test
    fun aFailedDrainWithNothingDueAndNothingWaitingSchedulesNoKick() {
        assertNull(
            DeliveryWorker.retryKickDelayFor(
                outcome = DrainOutcome.FAILED,
                earliestFutureAttemptMillis = null,
                anyRowDue = false,
                nowMillis = 10_000L,
            ),
        )
    }

    /**
     * A failed drain can leave rows *due* with none waiting: a `Retry-After`
     * of zero (or an HTTP date already past, which the classifier reads the
     * same way) parks the row at `now`, and the rest of the batch it stopped
     * in front of was never touched. Nothing is in the future for the DAO to
     * find, so without a floor there would be no kick at all and the due rows
     * would sit until the 15-minute periodic net — where `Result.retry()`
     * used to wake the worker in 30 s. The ladder's base is that floor, and it
     * is also what keeps a server answering `Retry-After: 0` forever from
     * turning the kick into a hot loop: one drain per base, never tighter.
     */
    @Test
    fun aFailedDrainWithARowDueAndNothingWaitingKicksAfterTheLadderBase() {
        assertEquals(
            DeliveryCoordinator.BACKOFF_BASE_MILLIS,
            DeliveryWorker.retryKickDelayFor(
                outcome = DrainOutcome.FAILED,
                earliestFutureAttemptMillis = null,
                anyRowDue = true,
                nowMillis = 10_000L,
            ),
        )
    }

    /**
     * With both a due row and a waiting one, the waiting row paces the kick —
     * the base is only for when nothing waits.
     */
    @Test
    fun withARowDueAndARowWaitingTheWaitingRowPacesTheKick() {
        assertEquals(
            "a row waiting 5 s out is sooner than the base",
            5_000L,
            DeliveryWorker.retryKickDelayFor(
                outcome = DrainOutcome.FAILED,
                earliestFutureAttemptMillis = 15_000L,
                anyRowDue = true,
                nowMillis = 10_000L,
            ),
        )
        assertEquals(
            "a row waiting a minute out paces the kick even with due rows behind it — " +
                "kicking at the base would walk them into the limiter that parked it",
            60_000L,
            DeliveryWorker.retryKickDelayFor(
                outcome = DrainOutcome.FAILED,
                earliestFutureAttemptMillis = 70_000L,
                anyRowDue = true,
                nowMillis = 10_000L,
            ),
        )
    }

    /**
     * A row can be waiting after a healthy drain too — one that failed on an
     * earlier pass and was skipped by this one's due-gate. Only a failure earns
     * a kick: a DONE drain's waiting rows are the next trigger's or the
     * periodic kick's, and a MORE_PAGES drain has a continuation queued already.
     */
    @Test
    fun onlyAFailedDrainEarnsAKickEvenWhenARowIsWaitingOrDue() {
        assertNull(
            DeliveryWorker.retryKickDelayFor(
                outcome = DrainOutcome.DONE,
                earliestFutureAttemptMillis = 35_000L,
                anyRowDue = true,
                nowMillis = 10_000L,
            ),
        )
        assertNull(
            DeliveryWorker.retryKickDelayFor(
                outcome = DrainOutcome.MORE_PAGES,
                earliestFutureAttemptMillis = 35_000L,
                anyRowDue = true,
                nowMillis = 10_000L,
            ),
        )
    }
}
