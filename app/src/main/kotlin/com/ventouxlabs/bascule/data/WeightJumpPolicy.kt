package com.ventouxlabs.bascule.data

import kotlin.math.abs

/**
 * A scale is shared: a second person stepping on it must not auto-sync as the
 * user. A reading that moves more than [MAX_JUMP_KG] from the last accepted
 * one is held for confirmation instead. Flat threshold, no time component.
 */
object WeightJumpPolicy {
    const val MAX_JUMP_KG = 10.0

    /** No baseline means nothing to compare against, so no hold. */
    fun isJump(baselineKg: Double?, candidateKg: Double): Boolean =
        baselineKg != null && abs(candidateKg - baselineKg) > MAX_JUMP_KG
}
