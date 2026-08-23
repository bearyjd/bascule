package com.ventouxlabs.bascule.diagnostics

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
}
