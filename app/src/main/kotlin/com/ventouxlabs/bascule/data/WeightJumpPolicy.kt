package com.ventouxlabs.bascule.data

import kotlin.math.abs

/**
 * A scale is shared: a second person stepping on it must not auto-sync as the
 * user. A reading that moves more than [MAX_JUMP_KG] from the last accepted
 * one is held for confirmation instead. Flat threshold, no time component.
 */
object WeightJumpPolicy {
    const val MAX_JUMP_KG = 10.0

    /** Absorbs double subtraction noise (~1e-14) so a true 10.0 kg change at 0.1 kg resolution is not held. */
    const val TOLERANCE_KG = 1e-6

    /** No baseline means nothing to compare against, so no hold. */
    fun isJump(baselineKg: Double?, candidateKg: Double): Boolean =
        baselineKg != null && abs(candidateKg - baselineKg) > MAX_JUMP_KG + TOLERANCE_KG
}
