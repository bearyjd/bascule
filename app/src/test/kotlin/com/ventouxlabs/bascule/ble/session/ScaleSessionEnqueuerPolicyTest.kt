package com.ventouxlabs.bascule.ble.session

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * pr-1-review-correctness.md M3. The policy decision is split out from
 * [WorkManagerScaleSessionEnqueuer] because a real `WorkManager` is not
 * constructible in this lane — see [com.ventouxlabs.bascule.ble.ScanBroadcastReceiver]'s
 * test for the `WorkManagerTestInitHelper` limitation.
 */
class ScaleSessionEnqueuerPolicyTest {

    @Test
    fun aRunningSessionIsKept() {
        assertEquals(
            ExistingWorkPolicy.KEEP,
            existingWorkPolicyFor(listOf(WorkInfo.State.RUNNING)),
        )
    }

    @Test
    fun aMerelyEnqueuedRequestIsReplacedSoTheFresherSeenAtWins() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            existingWorkPolicyFor(listOf(WorkInfo.State.ENQUEUED)),
        )
    }

    @Test
    fun aBlockedRequestIsReplaced() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            existingWorkPolicyFor(listOf(WorkInfo.State.BLOCKED)),
        )
    }

    @Test
    fun noExistingWorkIsReplaced() {
        assertEquals(ExistingWorkPolicy.REPLACE, existingWorkPolicyFor(emptyList()))
    }

    @Test
    fun finishedHistoryDoesNotSuppressANewRequest() {
        val states = listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)

        assertEquals(ExistingWorkPolicy.REPLACE, existingWorkPolicyFor(states))
    }

    @Test
    fun aRunningSessionWinsOverFinishedHistory() {
        val states = listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.RUNNING)

        assertEquals(ExistingWorkPolicy.KEEP, existingWorkPolicyFor(states))
    }
}
