package com.ventouxlabs.bascule.delivery

import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingStatus
import kotlin.math.abs

/**
 * 00-design.md §3.3. A candidate is a duplicate when source matches, the user
 * matches (two nulls count as equal), the weights are within [WEIGHT_TOLERANCE_KG]
 * and the capture times are within [TIME_WINDOW_MILLIS].
 *
 * The same two constants also drive the ADR-003 remote-duplicate check, so the
 * local and remote rules cannot drift.
 */
object DedupPolicy {

    /**
     * Sized by human physiology, not by scale precision. The BF720's confirmed
     * resolution is 0.01 kg with a ×0.005 kg raw multiplier
     * (docs/prp/03-hardware-validation.md §3, §5), so 200 g is 20–40 LSBs, not
     * the "2 LSBs" an earlier draft claimed off a guessed 0.1 kg resolution.
     * The value stands on its own reasoning: 200 g absorbs a re-broadcast final
     * frame and any kg/lb round-trip rounding, while staying far too tight to
     * collapse two genuinely different weigh-ins (post-workout, post-meal).
     * Expressed in kg, the canonical stored unit, so it does not change meaning
     * when the display unit changes.
     */
    const val WEIGHT_TOLERANCE_KG = 0.20

    /**
     * Covers "step on, scale repeats the final frame, powers off, user steps on
     * again to double-check". Longer windows start eating legitimate
     * before/after measurements.
     */
    const val TIME_WINDOW_MILLIS = 300_000L

    fun isDuplicate(candidate: ReadingEntity, existing: ReadingEntity): Boolean =
        // A declined row is another person's weight; dedupping against it would
        // turn one correct rejection into two lost readings.
        existing.status != ReadingStatus.DECLINED &&
            candidate.source == existing.source &&
            candidate.userIndex == existing.userIndex &&
            abs(candidate.weightKg - existing.weightKg) <= WEIGHT_TOLERANCE_KG &&
            abs(candidate.capturedAtMillis - existing.capturedAtMillis) <= TIME_WINDOW_MILLIS

    fun isDuplicateOfAny(candidate: ReadingEntity, corpus: List<ReadingEntity>): Boolean =
        corpus.any { isDuplicate(candidate, it) }
}
