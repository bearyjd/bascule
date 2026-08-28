package com.ventouxlabs.bascule.delivery

import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression (round-3 HIGH #3). Pagination and failure both used to map to
 * `Result.retry()`, so WorkManager's exponential ladder was applied to a healthy
 * multi-page drain — a `BLOCKED_AUTH` recovery of several hundred rows took
 * hours instead of minutes.
 *
 * Only the mapping is covered here: a real `WorkManager` is not constructible in
 * this project's JUnit lane, so the enqueue itself is not verified — see
 * [com.ventouxlabs.bascule.ble.session.ScaleSessionEnqueuerPolicyTest] for the
 * same split.
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

    @Test
    fun onlyARealFailureEarnsTheBackoffLadder() {
        assertEquals(ListenableWorker.Result.retry(), DeliveryWorker.resultFor(DrainOutcome.FAILED))
    }
}
