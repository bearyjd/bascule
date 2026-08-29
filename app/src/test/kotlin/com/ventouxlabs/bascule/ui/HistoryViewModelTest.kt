package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.data.ErrorClass
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.delivery.DeliveryTrigger
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import com.ventouxlabs.bascule.ui.fake.readingFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** WP-23: `ui/HistoryScreen.kt`'s ViewModel — ranking, banners, and row actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * `uiState` is `WhileSubscribed`, so it computes nothing until something
     * collects it — a test that only reads `.value` would see the initial state
     * forever. Collecting in `backgroundScope` is the runTest-native equivalent
     * of the screen being on-screen, and it is cancelled when the test ends.
     *
     * The compute dispatcher is the rule's own, so it shares runTest's scheduler
     * and `advanceUntilIdle()` still drives the `flowOn` hop deterministically.
     */
    private fun TestScope.viewModel(
        dao: FakeReadingDao,
        now: Long = 0L,
        diagnostics: InMemoryDiagnosticsCounters = InMemoryDiagnosticsCounters(),
        deliveryTrigger: DeliveryTrigger? = null,
        configStore: FakeConfigStore = FakeConfigStore(),
    ): HistoryViewModel {
        val vm = HistoryViewModel(
            dao,
            diagnostics,
            configStore,
            nowMillis = { now },
            deliveryTrigger = deliveryTrigger,
            computeDispatcher = mainDispatcherRule.dispatcher,
        )
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun heldConfirmRowsRankAboveAllOthers() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "sent", status = ReadingStatus.SENT, capturedAtMillis = 100))
        dao.insert(readingFixture(id = "failed", status = ReadingStatus.FAILED_PERMANENT, capturedAtMillis = 200))
        dao.insert(readingFixture(id = "blocked", status = ReadingStatus.BLOCKED_AUTH, capturedAtMillis = 300))
        dao.insert(readingFixture(id = "pending", status = ReadingStatus.PENDING, capturedAtMillis = 400))
        dao.insert(readingFixture(id = "declined", status = ReadingStatus.DECLINED, capturedAtMillis = 500))
        dao.insert(readingFixture(id = "held", status = ReadingStatus.HELD_CONFIRM, capturedAtMillis = 50))
        val vm = viewModel(dao)
        advanceUntilIdle()

        assertEquals("held", vm.uiState.value.rows.first().id)
    }

    @Test
    fun sentRowsRankLast() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "sent", status = ReadingStatus.SENT, capturedAtMillis = 500))
        dao.insert(readingFixture(id = "pending", status = ReadingStatus.PENDING, capturedAtMillis = 100))
        dao.insert(readingFixture(id = "declined", status = ReadingStatus.DECLINED, capturedAtMillis = 100))
        val vm = viewModel(dao)
        advanceUntilIdle()

        assertEquals(
            "SENT ranks last even though it is the most recently captured row",
            "sent",
            vm.uiState.value.rows.last().id,
        )
    }

    @Test
    fun sortsByCapturedAtWithinAStatusGroup() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "older", status = ReadingStatus.PENDING, capturedAtMillis = 100))
        dao.insert(readingFixture(id = "newer", status = ReadingStatus.PENDING, capturedAtMillis = 200))
        val vm = viewModel(dao)
        advanceUntilIdle()

        assertEquals(listOf("newer", "older"), vm.uiState.value.rows.map { it.id })
    }

    @Test
    fun blockedAuthBannerIsShownWhenAnyRowIsBlocked() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(status = ReadingStatus.BLOCKED_AUTH))
        val vm = viewModel(dao)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.hasBlockedAuth)
    }

    @Test
    fun confirmTransitionsRowToPending() = runTest {
        val dao = FakeReadingDao()
        val row = readingFixture(id = "r1", status = ReadingStatus.HELD_CONFIRM)
        dao.insert(row)
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.confirm(row)
        advanceUntilIdle()

        assertEquals(ReadingStatus.PENDING, dao.rows.value.single { it.id == "r1" }.status)
    }

    @Test
    fun declineTransitionsRowToDeclined() = runTest {
        val dao = FakeReadingDao()
        val row = readingFixture(id = "r1", status = ReadingStatus.HELD_CONFIRM)
        dao.insert(row)
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.decline(row)
        advanceUntilIdle()

        assertEquals(ReadingStatus.DECLINED, dao.rows.value.single { it.id == "r1" }.status)
    }

    @Test
    fun retryTransitionsFailedPermanentRowToPending() = runTest {
        val dao = FakeReadingDao()
        val row = readingFixture(id = "r1", status = ReadingStatus.FAILED_PERMANENT)
        dao.insert(row)
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.retry(row)
        advanceUntilIdle()

        assertEquals(ReadingStatus.PENDING, dao.rows.value.single { it.id == "r1" }.status)
    }

    /**
     * `00-design.md` §5: re-entering `PENDING` resets `attemptCount`, not
     * only `retryEpochMillis` — §3.4's backoff is `min(30s * 2^(attemptCount-1),
     * 15min)`, so a row that had already failed many times would land on the
     * 15-minute cap on the very first retry if this were left stale.
     */
    @Test
    fun retryResetsAttemptCountAndClearsTheStaleFailureReason() = runTest {
        val dao = FakeReadingDao()
        val row = readingFixture(
            id = "r1",
            status = ReadingStatus.FAILED_PERMANENT,
            attemptCount = 7,
            lastError = "401 Unauthorized",
            lastErrorClass = ErrorClass.AUTH,
        )
        dao.insert(row)
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.retry(row)
        advanceUntilIdle()

        val updated = dao.rows.value.single { it.id == "r1" }
        assertEquals(0, updated.attemptCount)
        assertNull(updated.lastError)
        assertNull(updated.lastErrorClass)
    }

    /**
     * C14: the `deliveryTrigger` collaborator was left null in every test here, so
     * nothing covered the guard that decides whether a row action kicks off a
     * drain. Both sides matter: a confirm makes a row deliverable and must not
     * wait for the next periodic run, and a decline is terminal (ADR-006) — a
     * drain for it would be pure wakeup cost for a row that can never be sent.
     */
    @Test
    fun confirmTriggersADrainAndDeclineDoesNot() = runTest {
        val dao = FakeReadingDao()
        val trigger = FakeDeliveryTrigger()
        val confirmed = readingFixture(id = "yes", status = ReadingStatus.HELD_CONFIRM)
        val declined = readingFixture(id = "no", status = ReadingStatus.HELD_CONFIRM)
        dao.insert(confirmed)
        dao.insert(declined)
        val vm = viewModel(dao, deliveryTrigger = trigger)
        advanceUntilIdle()

        vm.confirm(confirmed)
        advanceUntilIdle()
        assertEquals("a confirmed row is deliverable now, not at the next periodic drain", 1, trigger.triggerCount)

        vm.decline(declined)
        advanceUntilIdle()
        assertEquals("DECLINED is terminal — draining for it would never send anything", 1, trigger.triggerCount)
    }

    @Test
    fun retryTriggersADrain() = runTest {
        val dao = FakeReadingDao()
        val trigger = FakeDeliveryTrigger()
        val row = readingFixture(id = "r1", status = ReadingStatus.FAILED_PERMANENT)
        dao.insert(row)
        val vm = viewModel(dao, deliveryTrigger = trigger)
        advanceUntilIdle()

        vm.retry(row)
        advanceUntilIdle()

        assertEquals(1, trigger.triggerCount)
    }

    /**
     * The §3.4 backoff gate is keyed on `nextAttemptMillis`, so a "Retry" tap that
     * left it set would enqueue a drain that then skips the very row the user
     * asked for — a button that visibly does nothing.
     */
    @Test
    fun retryClearsTheBackoffGateSoTheDrainItTriggersActuallySeesTheRow() = runTest {
        val dao = FakeReadingDao()
        val row = readingFixture(
            id = "r1",
            status = ReadingStatus.FAILED_PERMANENT,
            attemptCount = 7,
            nextAttemptMillis = Long.MAX_VALUE,
        )
        dao.insert(row)
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.retry(row)
        advanceUntilIdle()

        assertNull(dao.rows.value.single { it.id == "r1" }.nextAttemptMillis)
        assertEquals(listOf("r1"), dao.pending(nowMillis = 0L, limit = 10).map { it.id })
    }

    @Test
    fun showsPendingBacklogAge() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(status = ReadingStatus.PENDING, capturedAtMillis = 1_000))
        val vm = viewModel(dao, now = 1_000 + 60_000)
        advanceUntilIdle()

        assertEquals(60_000L, vm.uiState.value.oldestPendingAgeMillis)
    }

    @Test
    fun noPendingRowsMeansNoBacklogAge() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(status = ReadingStatus.SENT))
        val vm = viewModel(dao)
        advanceUntilIdle()

        assertNull(vm.uiState.value.oldestPendingAgeMillis)
    }

    @Test
    fun showsDiagnosticsCounters() = runTest {
        val diagnostics = InMemoryDiagnosticsCounters()
        diagnostics.increment(DiagnosticsCounterKey.MALFORMED_COUNT)
        diagnostics.increment(DiagnosticsCounterKey.MALFORMED_COUNT)
        val dao = FakeReadingDao()
        val vm = viewModel(dao, diagnostics = diagnostics)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.counters[DiagnosticsCounterKey.MALFORMED_COUNT])
    }

    /**
     * `NO_MEASUREMENT` (E7) increments with no corresponding row insert — a
     * session that produced no reading is exactly the case that inserts
     * nothing. Proves the counter reaches `uiState` on its own, after
     * construction, with zero row changes — not merely that a pre-existing
     * count is read once at startup (the gap `showsDiagnosticsCounters`
     * alone cannot rule out, since it increments before the ViewModel exists).
     */
    @Test
    fun diagnosticsCounterUpdatesReachUiStateWithoutAnyRowChange() = runTest {
        val diagnostics = InMemoryDiagnosticsCounters()
        val dao = FakeReadingDao()
        val vm = viewModel(dao, diagnostics = diagnostics)
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.counters[DiagnosticsCounterKey.NO_MEASUREMENT] ?: 0)

        diagnostics.increment(DiagnosticsCounterKey.NO_MEASUREMENT)
        advanceUntilIdle()

        assertEquals(
            "no row was ever inserted — this must reach uiState through the counters flow alone",
            1,
            vm.uiState.value.counters[DiagnosticsCounterKey.NO_MEASUREMENT],
        )
        assertTrue("no row insert should have happened as a side effect of the counter bump", dao.rows.value.isEmpty())
    }
}
