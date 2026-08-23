package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import com.ventouxlabs.bascule.ui.fake.readingFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun viewModel(dao: FakeReadingDao, now: Long = 0L) =
        HistoryViewModel(dao, InMemoryDiagnosticsCounters(), nowMillis = { now })

    @Test
    fun heldConfirmRowsRankAboveAllOthers() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(id = "sent", status = ReadingStatus.SENT, capturedAtMillis = 100))
        dao.insert(readingFixture(id = "failed", status = ReadingStatus.FAILED_PERMANENT, capturedAtMillis = 200))
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
        val vm = HistoryViewModel(dao, diagnostics)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.counters[DiagnosticsCounterKey.MALFORMED_COUNT])
    }
}
