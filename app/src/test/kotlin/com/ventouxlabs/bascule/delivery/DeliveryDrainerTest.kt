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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

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

    /**
     * Regression (correctness H2): §3.4's per-row ladder was specified but never
     * implemented — `drain()` submitted every PENDING row on every trigger. Any
     * unrelated trigger (a manual entry saved, a token saved, a scale capture)
     * therefore resubmitted the whole backing-off set instantly.
     */
    @Test
    fun aRowStillInsideItsBackoffWindowIsNotSubmitted() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "backing-off", attemptCount = 1, nextAttemptMillis = 60_000L))
        val api = FakeDeliveryApi()

        drainer(dao, api, now = 59_999L).drain()

        assertTrue("the backoff has not elapsed; this row must not be submitted", api.submittedReadingIds.isEmpty())
        assertEquals(0, api.recentReadingsCallCount)
    }

    @Test
    fun aRowIsSubmittedAgainOnceItsBackoffWindowHasElapsed() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "due", attemptCount = 1, nextAttemptMillis = 60_000L))
        val api = FakeDeliveryApi()

        drainer(dao, api, now = 60_000L).drain()

        assertEquals(listOf("due"), api.submittedReadingIds)
    }

    /** A row backing off must not hold up a freshly captured one behind it. */
    @Test
    fun aBackingOffRowDoesNotStarveADueRow() = runTest {
        val dao = FakeReadingDao()
        dao.insert(
            readingFixture(
                id = "old-backing-off",
                capturedAtMillis = 1L,
                attemptCount = 1,
                nextAttemptMillis = 99_999L,
            ),
        )
        dao.insert(readingFixture(id = "fresh", capturedAtMillis = 2L))
        val api = FakeDeliveryApi()

        drainer(dao, api, now = 10_000L).drain()

        assertEquals(listOf("fresh"), api.submittedReadingIds)
    }

    @Test
    fun aTransientFailureSchedulesTheNextAttemptOnTheDesignLadder() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "row-1"))
        val api = FakeDeliveryApi()
        api.enqueueSubmitResult(SubmitResult.TransientFailure("socket hang up", retryAfter = null))

        drainer(dao, api, now = 10_000L).drain()

        val row = dao.rows.value.single()
        assertEquals(
            "first failure waits §3.4's 30 s base",
            10_000L + DeliveryCoordinator.BACKOFF_BASE_MILLIS,
            row.nextAttemptMillis,
        )
    }

    /**
     * Regression (correctness H2, second half): `ResponseClassifier` parsed
     * `Retry-After` into `TransientFailure.retryAfter` and the drainer dropped it
     * on the floor. The server knows about its own rate limit; the ladder does not.
     */
    @Test
    fun aServerRetryAfterOverridesTheLadderAndStopsTheRestOfThePass() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "rate-limited", capturedAtMillis = 1L))
        dao.insert(readingFixture(id = "behind-it", capturedAtMillis = 2L))
        val api = FakeDeliveryApi()
        api.enqueueSubmitResult(SubmitResult.TransientFailure("server returned 429", retryAfter = 60.seconds))

        val retryNeeded = drainer(dao, api, now = 10_000L).drain()

        assertTrue(retryNeeded)
        assertEquals(
            "submitting the rest of the batch would walk into the same rate limit",
            listOf("rate-limited"),
            api.submittedReadingIds,
        )
        assertEquals(
            10_000L + 60_000L,
            dao.rows.value.single { it.id == "rate-limited" }.nextAttemptMillis,
        )
    }

    /** §4.5: a hostile `Retry-After` must not be able to park a reading indefinitely. */
    @Test
    fun anAbsurdRetryAfterIsClampedToTheOneHourCeiling() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "row-1"))
        val api = FakeDeliveryApi()
        api.enqueueSubmitResult(SubmitResult.TransientFailure("server returned 429", retryAfter = 30.days))

        drainer(dao, api, now = 0L).drain()

        assertEquals(
            DeliveryCoordinator.MAX_RETRY_AFTER_MILLIS,
            dao.rows.value.single().nextAttemptMillis,
        )
    }

    /**
     * Regression (correctness H4 / performance H4): `pending()` had no `LIMIT`
     * and the drain ran strictly sequentially, so an `unblockAuthRows()` recovery
     * could hit WorkManager's 10-minute ceiling and be killed mid-drain — then
     * restart from the top, re-walking every row it had already processed.
     */
    @Test
    fun aDrainIsBoundedToOneBatchAndAsksToBeResumedWhileRowsRemain() = runTest {
        val dao = FakeReadingDao()
        repeat(DeliveryCoordinator.DRAIN_BATCH_LIMIT + 5) { index ->
            dao.insert(readingFixture(id = "row-$index", capturedAtMillis = index.toLong()))
        }
        val api = FakeDeliveryApi()

        val retryNeeded = drainer(dao, api).drain()

        assertEquals(DeliveryCoordinator.DRAIN_BATCH_LIMIT, api.submittedReadingIds.size)
        assertTrue("rows remain beyond the batch — the worker must be asked to run again", retryNeeded)
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
