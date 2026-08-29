package com.ventouxlabs.bascule.diagnostics

import kotlinx.coroutines.flow.StateFlow

/**
 * Registry keys, one per row of `01-plan.md` §2.1's counter table. Each key has
 * exactly one owning package; `PersistentDiagnosticsCountersTest` (WP-26) checks
 * that mechanically once every package has landed.
 */
enum class DiagnosticsCounterKey {
    INCOMPATIBLE_STREAK,
    MISSED_QUOTA,
    MALFORMED_COUNT,
    DUPLICATE_STABLE_SUPPRESSED,
    UNPAIRABLE_FRAMES_DROPPED,
    REGISTRATION_REJECTED,
    NO_MEASUREMENT,
    DUPLICATES_SUPPRESSED,
    DROPPED_OTHER_USER,
    REMOTE_DUPLICATES_SUPPRESSED,
}

/**
 * Every later package increments through this interface rather than inventing
 * its own field (`01-plan.md` §2.1). `INCOMPATIBLE_STREAK` is a streak, not a
 * running total, so the surface includes [reset] alongside [increment] — E4
 * resets it on the next successful discovery.
 *
 * `InMemoryDiagnosticsCounters` is the test double used until WP-26 provides a
 * persistent implementation behind this same interface.
 */
interface DiagnosticsCounters {
    /** Returns the new value after incrementing. */
    fun increment(key: DiagnosticsCounterKey): Int
    fun reset(key: DiagnosticsCounterKey)
    fun value(key: DiagnosticsCounterKey): Int

    /**
     * A full snapshot, updated on every [increment]/[reset] — HistoryScreen's
     * diagnostics section needs this rather than [value] alone, since a
     * counter can change (E7's `NO_MEASUREMENT`, most notably: a session that
     * produced no reading inserts no `ReadingEntity` row by definition) with
     * no corresponding row change for `HistoryViewModel`'s own
     * `ReadingDao.observeAll()` collection to notice.
     */
    fun observeAll(): StateFlow<Map<DiagnosticsCounterKey, Int>>
}
