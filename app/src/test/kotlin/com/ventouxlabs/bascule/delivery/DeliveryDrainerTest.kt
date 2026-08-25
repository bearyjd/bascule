package com.ventouxlabs.bascule.delivery

import com.ventouxlabs.bascule.data.ErrorClass
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.delivery.fake.FakeDeliveryApi
import com.ventouxlabs.bascule.network.RecentResult
import com.ventouxlabs.bascule.network.RemoteReading
import com.ventouxlabs.bascule.network.RuntimeApi
import com.ventouxlabs.bascule.network.SubmitResult
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.readingFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryDrainerTest {

    private fun drainer(dao: FakeReadingDao, api: FakeDeliveryApi, now: Long = 10_000L) =
        DeliveryDrainer(dao, RuntimeApi(api, WeightUnit.KILOGRAMS), clock = { now })

    @Test
    fun fetchesRemoteRecentReadingsExactlyOncePerDrainRegardlessOfPendingRowCount() = runTest {
        val dao = FakeReadingDao()
        repeat(5) { index -> dao.insert(readingFixture(id = "row-$index", capturedAtMillis = index.toLong())) }
        val api = FakeDeliveryApi()
        drainer(dao, api).drain()
        assertEquals(1, api.recentReadingsCallCount)
        assertEquals(5, api.submittedReadingIds.size)
    }

    @Test
    fun anEmptyPendingQueueNeverCallsTheRemoteApiAtAll() = runTest {
        val dao = FakeReadingDao()
        val api = FakeDeliveryApi()
        val retryNeeded = drainer(dao, api).drain()
        assertEquals(0, api.recentReadingsCallCount)
        assertFalse(retryNeeded)
    }

    @Test
    fun aRowMatchingARemoteRecentReadingIsMarkedSentAsARemoteDuplicateWithoutSubmitting() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "row-1", weightKg = 70.0, capturedAtMillis = 1_000L))
        val api = FakeDeliveryApi(recentResult = RecentResult.Readings(listOf(RemoteReading(70.0, 1_000L))))
        drainer(dao, api).drain()
        assertTrue(api.submittedReadingIds.isEmpty())
        val row = dao.rows.value.single()
        assertEquals(ReadingStatus.SENT, row.status)
        assertTrue(row.remoteDuplicate)
    }

    @Test
    fun anAcceptedSubmissionMarksTheRowSent() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "row-1"))
        val api = FakeDeliveryApi()
        api.enqueueSubmitResult(SubmitResult.Accepted(emptySet()))
        drainer(dao, api).drain()
        assertEquals(ReadingStatus.SENT, dao.rows.value.single().status)
    }

    @Test
    fun anAuthRejectionBlocksAllPendingRowsAndStopsWithoutRetry() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "row-1"))
        dao.insert(readingFixture(id = "row-2"))
        val api = FakeDeliveryApi()
        api.enqueueSubmitResult(SubmitResult.AuthRejected(401))
        val retryNeeded = drainer(dao, api).drain()
        assertFalse(retryNeeded)
        assertTrue(dao.rows.value.all { it.status == ReadingStatus.BLOCKED_AUTH })
        // AuthRejected short-circuits the whole drain — the second row is never submitted.
        assertEquals(1, api.submittedReadingIds.size)
    }

    @Test
    fun aPermanentRejectionFailsTheRowWithoutRequestingRetry() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "row-1"))
        val api = FakeDeliveryApi()
        api.enqueueSubmitResult(SubmitResult.PermanentRejection(422, "bad payload"))
        val retryNeeded = drainer(dao, api).drain()
        assertFalse(retryNeeded)
        val row = dao.rows.value.single()
        assertEquals(ReadingStatus.FAILED_PERMANENT, row.status)
        assertEquals(ErrorClass.PERMANENT, row.lastErrorClass)
    }

    @Test
    fun aTransientFailureRequestsRetryAndLeavesTheRowPending() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "row-1"))
        val api = FakeDeliveryApi()
        api.enqueueSubmitResult(SubmitResult.TransientFailure("socket hang up", retryAfter = null))
        val retryNeeded = drainer(dao, api).drain()
        assertTrue(retryNeeded)
        val row = dao.rows.value.single()
        assertEquals(ReadingStatus.PENDING, row.status)
        assertEquals(1, row.attemptCount)
        assertEquals(ErrorClass.TRANSIENT, row.lastErrorClass)
    }

    @Test
    fun aRowPastTheRetryExpiryWindowFailsPermanentlyWithoutEverCallingSubmit() = runTest {
        val dao = FakeReadingDao()
        dao.insert(
            readingFixture(id = "row-1", attemptCount = 1, retryEpochMillis = 0L),
        )
        val api = FakeDeliveryApi()
        val now = DeliveryCoordinator.EXPIRY_MILLIS + 1
        drainer(dao, api, now = now).drain()
        assertTrue(api.submittedReadingIds.isEmpty())
        assertEquals(ReadingStatus.FAILED_PERMANENT, dao.rows.value.single().status)
    }
}
