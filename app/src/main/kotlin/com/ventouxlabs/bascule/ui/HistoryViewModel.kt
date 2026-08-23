package com.ventouxlabs.bascule.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounters
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val rows: List<ReadingEntity> = emptyList(),
    val hasBlockedAuth: Boolean = false,
    val hasFailedPermanent: Boolean = false,
    val oldestPendingAgeMillis: Long? = null,
    val counters: Map<DiagnosticsCounterKey, Int> = emptyMap(),
)

/**
 * WP-23: "the single answer to *did my weigh-in reach VitalForge*"
 * (`00-design.md` §5). Ranks `HELD_CONFIRM` above `BLOCKED_AUTH`/
 * `FAILED_PERMANENT` (surfaced as banners, since they're an app-wide
 * condition, not a per-row detail), above everything else, with `SENT`
 * ranked last — a fully-resolved row is the least useful thing to see first.
 *
 * The diagnostics counters shown here are process-lifetime only until WP-26
 * lands a persistent implementation (see [BasculeApplication.diagnosticsCounters]).
 */
class HistoryViewModel(
    private val dao: ReadingDao,
    private val diagnostics: DiagnosticsCounters,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /**
     * `DiagnosticsCounters` is combined alongside `dao.observeAll()`, not read
     * inside its collect block — a counter can change (E7's `NO_MEASUREMENT`,
     * most notably: a session that produced no reading inserts no row by
     * definition) with no corresponding row change to trigger a recompute.
     */
    val uiState: StateFlow<HistoryUiState> = combine(
        dao.observeAll(),
        diagnostics.observeAll(),
    ) { readings, counters ->
        HistoryUiState(
            rows = readings.sortedWith(rowOrdering),
            hasBlockedAuth = readings.any { it.status == ReadingStatus.BLOCKED_AUTH },
            hasFailedPermanent = readings.any { it.status == ReadingStatus.FAILED_PERMANENT },
            oldestPendingAgeMillis = readings
                .filter { it.status == ReadingStatus.PENDING }
                .minOfOrNull { it.capturedAtMillis }
                ?.let { nowMillis() - it },
            counters = counters,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())

    /** "Yes, that's me" — the row was correctly attributed after all. */
    fun confirm(reading: ReadingEntity) = updateStatus(reading, ReadingStatus.PENDING, resetRetryEpoch = true)

    /** "Not me" — terminal per ADR-006; no further action is ever offered on this row. */
    fun decline(reading: ReadingEntity) = updateStatus(reading, ReadingStatus.DECLINED, resetRetryEpoch = false)

    fun retry(reading: ReadingEntity) = updateStatus(reading, ReadingStatus.PENDING, resetRetryEpoch = true)

    /**
     * `00-design.md` §5: re-entering `PENDING` resets `retryEpochMillis` *and*
     * `attemptCount` — without the latter, §3.4's backoff
     * (`min(30s * 2^(attemptCount-1), 15min)`) lands at the 15-minute cap on
     * the very first retry of a row that had already failed many times, per
     * §8.6's own worked example. A stale failure reason from the old attempt
     * is also cleared, since the row is `PENDING` again, not still failed.
     */
    private fun updateStatus(reading: ReadingEntity, status: ReadingStatus, resetRetryEpoch: Boolean) {
        viewModelScope.launch {
            val now = nowMillis()
            dao.update(
                reading.copy(
                    status = status,
                    attemptCount = if (resetRetryEpoch) 0 else reading.attemptCount,
                    retryEpochMillis = if (resetRetryEpoch) now else reading.retryEpochMillis,
                    lastError = if (resetRetryEpoch) null else reading.lastError,
                    lastErrorClass = if (resetRetryEpoch) null else reading.lastErrorClass,
                ),
            )
        }
    }

    companion object {
        val statusRank = mapOf(
            ReadingStatus.HELD_CONFIRM to 0,
            ReadingStatus.BLOCKED_AUTH to 1,
            ReadingStatus.FAILED_PERMANENT to 1,
            ReadingStatus.PENDING to 2,
            ReadingStatus.DECLINED to 3,
            ReadingStatus.SENT to 4,
        )

        val rowOrdering = compareBy<ReadingEntity> { statusRank.getValue(it.status) }
            .thenByDescending { it.capturedAtMillis }

        fun factory(app: BasculeApplication) = viewModelFactory {
            initializer { HistoryViewModel(app.database.readingDao(), app.diagnosticsCounters) }
        }
    }
}
