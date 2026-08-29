package com.ventouxlabs.bascule.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounters
import com.ventouxlabs.bascule.delivery.DeliveryTrigger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Sort buckets, most-actionable first — declaration order *is* the sort order. */
private enum class StatusRank { NEEDS_CONFIRMATION, BLOCKED, PENDING, DECLINED, SENT }

data class HistoryUiState(
    val rows: List<ReadingEntity> = emptyList(),
    val hasBlockedAuth: Boolean = false,
    val hasFailedPermanent: Boolean = false,
    val oldestPendingAgeMillis: Long? = null,
    val counters: Map<DiagnosticsCounterKey, Int> = emptyMap(),
    val displayUnit: WeightUnit = WeightUnit.KILOGRAMS,
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
    private val configStore: ConfigStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val deliveryTrigger: DeliveryTrigger? = null,
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /**
     * `DiagnosticsCounters` and `configStore.displayUnit` are combined
     * alongside `dao.observeAll()`, not read inside its collect block — a
     * counter can change (E7's `NO_MEASUREMENT`, most notably: a session that
     * produced no reading inserts no row by definition) with no corresponding
     * row change to trigger a recompute, and the same is true of a unit
     * change made from the Config screen while History is on-screen.
     *
     * `flowOn` keeps the sort and the summary pass off `Dispatchers.Main`:
     * `stateIn` collects on the main dispatcher, so without it the whole
     * readings table was re-sorted on the UI thread on every emission — and a
     * drain emits once per row it updates. `WhileSubscribed` stops that work
     * entirely when the History screen is not on-screen.
     */
    val uiState: StateFlow<HistoryUiState> = combine(
        dao.observeAll(),
        diagnostics.observeAll(),
        configStore.displayUnit,
    ) { readings, counters, displayUnit ->
        val summary = summarize(readings)
        HistoryUiState(
            rows = readings.sortedWith(rowOrdering),
            hasBlockedAuth = summary.hasBlockedAuth,
            hasFailedPermanent = summary.hasFailedPermanent,
            oldestPendingAgeMillis = summary.oldestPendingCaptureMillis?.let { nowMillis() - it },
            counters = counters,
            displayUnit = displayUnit,
        )
    }.flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), HistoryUiState())

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
     *
     * `nextAttemptMillis` clears for the same reason: an explicit tap on
     * "Retry" is the user asking for this row *now*, so it must not stay parked
     * behind a backoff the previous attempt scheduled.
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
                    nextAttemptMillis = if (resetRetryEpoch) null else reading.nextAttemptMillis,
                ),
            )
            if (status == ReadingStatus.PENDING) deliveryTrigger?.triggerImmediateDrain()
        }
    }

    /** The three banner facts in one pass, rather than three more full scans of the table. */
    private data class Summary(
        val hasBlockedAuth: Boolean,
        val hasFailedPermanent: Boolean,
        val oldestPendingCaptureMillis: Long?,
    )

    private fun summarize(readings: List<ReadingEntity>): Summary {
        var hasBlockedAuth = false
        var hasFailedPermanent = false
        var oldestPending: Long? = null
        for (reading in readings) {
            when (reading.status) {
                ReadingStatus.BLOCKED_AUTH -> hasBlockedAuth = true
                ReadingStatus.FAILED_PERMANENT -> hasFailedPermanent = true
                ReadingStatus.PENDING ->
                    oldestPending = minOf(oldestPending ?: reading.capturedAtMillis, reading.capturedAtMillis)

                ReadingStatus.HELD_CONFIRM, ReadingStatus.SENT, ReadingStatus.DECLINED -> Unit
            }
        }
        return Summary(hasBlockedAuth, hasFailedPermanent, oldestPending)
    }

    companion object {
        /** This project's `stateIn` convention — outlives a configuration change, not a screen exit. */
        private const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L

        /**
         * A `when` rather than a map lookup so that adding a [ReadingStatus]
         * fails to compile here instead of throwing at render time.
         */
        private fun statusRank(status: ReadingStatus): StatusRank = when (status) {
            ReadingStatus.HELD_CONFIRM -> StatusRank.NEEDS_CONFIRMATION
            ReadingStatus.BLOCKED_AUTH, ReadingStatus.FAILED_PERMANENT -> StatusRank.BLOCKED
            ReadingStatus.PENDING -> StatusRank.PENDING
            ReadingStatus.DECLINED -> StatusRank.DECLINED
            ReadingStatus.SENT -> StatusRank.SENT
        }

        val rowOrdering = compareBy<ReadingEntity> { statusRank(it.status) }
            .thenByDescending { it.capturedAtMillis }

        fun factory(app: BasculeApplication) = viewModelFactory {
            initializer {
                HistoryViewModel(
                    app.database.readingDao(),
                    app.diagnosticsCounters,
                    app.configStore,
                    deliveryTrigger = app.deliveryTrigger,
                )
            }
        }
    }
}
