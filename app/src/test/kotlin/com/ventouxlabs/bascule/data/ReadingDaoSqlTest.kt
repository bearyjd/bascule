package com.ventouxlabs.bascule.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.delivery.DedupPolicy
import com.ventouxlabs.bascule.delivery.DeliveryCoordinator
import com.ventouxlabs.bascule.ui.fake.readingFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * These run against real SQLite, not [com.ventouxlabs.bascule.ui.fake.FakeReadingDao].
 *
 * Patterns P1 flagged the dedup guarantee as "tested against `DedupPolicy` while
 * production runs an untested SQL implementation". The two are not rival
 * implementations — `dedupCandidates` has no weight comparison at all, so it
 * cannot be the dedup rule; it is a *prefilter* that narrows the corpus, and
 * [DedupPolicy] is the predicate applied to what comes back. But the finding's
 * underlying worry is real and unaddressed: the prefilter had no test of its own,
 * and a prefilter that is *narrower* than the predicate silently drops duplicates
 * the policy would have caught. That property is pinned below.
 *
 * The drain query is here for the same reason — its ADR-006 status gate and its
 * §3.4 backoff gate are both SQL, and the fake that every other test runs against
 * is only as trustworthy as its agreement with the statement exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadingDaoSqlTest {

    private lateinit var db: BasculeDatabase
    private lateinit var dao: ReadingDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BasculeDatabase::class.java,
        ).build()
        dao = db.readingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun theDrainQuerySelectsPendingAndNothingElse() = runBlocking {
        ReadingStatus.entries.forEach { status ->
            dao.insert(readingFixture(id = status.name, status = status))
        }

        val drained = dao.pending(nowMillis = Long.MAX_VALUE, limit = 100).map { it.id }

        assertEquals(
            "ADR-006: HELD_CONFIRM must never reach the drain, and the gate is structural",
            listOf(ReadingStatus.PENDING.name),
            drained,
        )
    }

    @Test
    fun theDrainQueryExcludesRowsStillInsideTheirBackoffWindow() = runBlocking {
        dao.insert(readingFixture(id = "never-attempted", nextAttemptMillis = null))
        dao.insert(readingFixture(id = "due-exactly-now", nextAttemptMillis = 5_000L))
        dao.insert(readingFixture(id = "still-backing-off", nextAttemptMillis = 5_001L))

        val due = dao.pending(nowMillis = 5_000L, limit = 100).map { it.id }.toSet()

        assertEquals(setOf("never-attempted", "due-exactly-now"), due)
    }

    @Test
    fun theDrainQueryIsBoundedAndReturnsTheOldestCapturesFirst() = runBlocking {
        listOf(300L, 100L, 200L).forEach { at ->
            dao.insert(readingFixture(id = "row-$at", capturedAtMillis = at))
        }

        val page = dao.pending(nowMillis = Long.MAX_VALUE, limit = 2).map { it.id }

        assertEquals(listOf("row-100", "row-200"), page)
    }

    /**
     * The gate must not be applied after the LIMIT: a page's worth of
     * not-yet-due rows at the head of the capture-time ordering would then
     * starve every due row behind them, indefinitely.
     */
    @Test
    fun aBackingOffRowDoesNotConsumeABatchSlotAndStarveADueRowBehindIt() = runBlocking {
        dao.insert(readingFixture(id = "old-backing-off", capturedAtMillis = 1L, nextAttemptMillis = Long.MAX_VALUE))
        dao.insert(readingFixture(id = "fresh", capturedAtMillis = 2L))

        assertEquals(listOf("fresh"), dao.pending(nowMillis = 10_000L, limit = 1).map { it.id })
    }

    @Test
    fun unblockingAuthRowsClearsTheBackoffGateAndTheAttemptCount() = runBlocking {
        dao.insert(
            readingFixture(
                id = "blocked",
                status = ReadingStatus.BLOCKED_AUTH,
                attemptCount = 9,
                nextAttemptMillis = Long.MAX_VALUE,
            ),
        )

        dao.unblockAuthRows(nowMillis = 7_000L)

        val row = dao.pending(nowMillis = 7_000L, limit = 10).single()
        assertEquals("re-authenticating must not leave the backlog parked behind a stale backoff", "blocked", row.id)
        assertEquals(0, row.attemptCount)
        assertEquals(7_000L, row.retryEpochMillis)
        assertNull(row.nextAttemptMillis)
        assertNull(row.lastError)
    }

    /**
     * The load-bearing property (P1): the SQL prefilter is never *narrower* than
     * the Kotlin predicate. Every row [DedupPolicy] would call a duplicate must
     * survive `dedupCandidates`, or a duplicate reaches the table unnoticed.
     *
     * The corpus below straddles every clause the two share — status, source and
     * the time window — plus the ones only the predicate has, so the assertion
     * discriminates rather than trivially holding.
     */
    @Test
    fun theSqlPrefilterNeverExcludesARowTheDedupPolicyWouldCallADuplicate() = runBlocking {
        val candidate = readingFixture(id = "candidate", weightKg = 70.0, capturedAtMillis = 1_000_000L)
        val corpus = listOf(
            readingFixture(id = "exact", weightKg = 70.0, capturedAtMillis = 1_000_000L),
            readingFixture(id = "within-weight-tolerance", weightKg = 70.15, capturedAtMillis = 1_000_000L),
            readingFixture(id = "outside-weight-tolerance", weightKg = 75.0, capturedAtMillis = 1_000_000L),
            readingFixture(
                id = "at-window-edge",
                weightKg = 70.0,
                capturedAtMillis = 1_000_000L + DedupPolicy.TIME_WINDOW_MILLIS,
            ),
            readingFixture(
                id = "just-outside-window",
                weightKg = 70.0,
                capturedAtMillis = 1_000_000L + DedupPolicy.TIME_WINDOW_MILLIS + 1,
            ),
            readingFixture(id = "declined", weightKg = 70.0, capturedAtMillis = 1_000_000L)
                .copy(status = ReadingStatus.DECLINED),
            readingFixture(id = "sent", weightKg = 70.0, capturedAtMillis = 1_000_000L)
                .copy(status = ReadingStatus.SENT),
            readingFixture(id = "manual", weightKg = 70.0, capturedAtMillis = 1_000_000L)
                .copy(source = ReadingSource.MANUAL),
            readingFixture(id = "other-user", weightKg = 70.0, capturedAtMillis = 1_000_000L)
                .copy(userIndex = 3),
        )
        corpus.forEach { dao.insert(it) }

        val prefiltered = dao.dedupCandidates(
            ReadingSource.SCALE.name,
            candidate.capturedAtMillis - DedupPolicy.TIME_WINDOW_MILLIS,
            candidate.capturedAtMillis + DedupPolicy.TIME_WINDOW_MILLIS,
        )
        val policyDuplicates = corpus.filter { DedupPolicy.isDuplicate(candidate, it) }.map { it.id }.toSet()

        assertEquals(
            "the corpus must actually contain duplicates, or this assertion proves nothing. " +
                "`sent` is one of them: an already-delivered reading still suppresses a re-capture, " +
                "and DECLINED is the only status §3.3 excludes",
            setOf("exact", "within-weight-tolerance", "at-window-edge", "sent"),
            policyDuplicates,
        )
        assertTrue(
            "a prefilter narrower than the predicate silently lets a duplicate through",
            prefiltered.map { it.id }.containsAll(policyDuplicates),
        )
        // And the predicate still has to do real work: the prefilter alone is not the rule.
        assertTrue(
            "the prefilter has no weight clause, so it must return non-duplicates too",
            prefiltered.size > policyDuplicates.size,
        )
    }

    /**
     * §3.3's `source` clause is what keeps a manual entry out of the scale dedup
     * corpus. `ReadingIngestor` passes `SCALE` as a literal rather than the
     * candidate's own source — correct today because it only ever ingests scale
     * readings, and pinned here so a future manual caller has to notice.
     */
    @Test
    fun theDedupCorpusIsScopedToOneSource() = runBlocking {
        dao.insert(readingFixture(id = "scale", weightKg = 70.0, capturedAtMillis = 1_000L))
        dao.insert(
            readingFixture(id = "manual", weightKg = 70.0, capturedAtMillis = 1_000L)
                .copy(source = ReadingSource.MANUAL),
        )

        val corpus = dao.dedupCandidates(ReadingSource.SCALE.name, 0L, 2_000L)

        assertEquals(listOf("scale"), corpus.map { it.id })
    }

    @Test
    fun theBatchLimitConstantIsWhatTheDrainQueryActuallyHonours() = runBlocking {
        repeat(DeliveryCoordinator.DRAIN_BATCH_LIMIT + 3) { index ->
            dao.insert(readingFixture(id = "row-$index", capturedAtMillis = index.toLong()))
        }

        assertEquals(
            DeliveryCoordinator.DRAIN_BATCH_LIMIT,
            dao.pending(nowMillis = Long.MAX_VALUE, limit = DeliveryCoordinator.DRAIN_BATCH_LIMIT).size,
        )
    }
}
