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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dao.observeAll().collect { readings ->
                _uiState.value = HistoryUiState(
                    rows = readings.sortedWith(rowOrdering),
                    hasBlockedAuth = readings.any { it.status == ReadingStatus.BLOCKED_AUTH },
                    hasFailedPermanent = readings.any { it.status == ReadingStatus.FAILED_PERMANENT },
                    oldestPendingAgeMillis = readings
                        .filter { it.status == ReadingStatus.PENDING }
                        .minOfOrNull { it.capturedAtMillis }
                        ?.let { nowMillis() - it },
                    counters = DiagnosticsCounterKey.entries.associateWith(diagnostics::value),
                )
            }
        }
    }

    /** "Yes, that's me" — the row was correctly attributed after all. */
    fun confirm(reading: ReadingEntity) = updateStatus(reading, ReadingStatus.PENDING, resetRetryEpoch = true)

    /** "Not me" — terminal per ADR-006; no further action is ever offered on this row. */
    fun decline(reading: ReadingEntity) = updateStatus(reading, ReadingStatus.DECLINED, resetRetryEpoch = false)

    fun retry(reading: ReadingEntity) = updateStatus(reading, ReadingStatus.PENDING, resetRetryEpoch = true)

    private fun updateStatus(reading: ReadingEntity, status: ReadingStatus, resetRetryEpoch: Boolean) {
        viewModelScope.launch {
            val now = nowMillis()
            dao.update(
                reading.copy(
                    status = status,
                    retryEpochMillis = if (resetRetryEpoch) now else reading.retryEpochMillis,
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
