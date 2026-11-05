package com.ventouxlabs.bascule.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightJumpPolicyTest {

    @Test
    fun theThresholdIsTenKilograms() {
        assertEquals(10.0, WeightJumpPolicy.MAX_JUMP_KG, 0.0)
    }

    @Test
    fun noBaselineIsNeverAJump() {
        assertFalse(WeightJumpPolicy.isJump(baselineKg = null, candidateKg = 200.0))
    }

    @Test
    fun aRiseBeyondTheThresholdIsAJump() {
        assertTrue(WeightJumpPolicy.isJump(baselineKg = 90.0, candidateKg = 100.5))
    }

    @Test
    fun aDropBeyondTheThresholdIsAJump() {
        assertTrue(WeightJumpPolicy.isJump(baselineKg = 90.0, candidateKg = 66.5))
    }

    @Test
    fun exactlyTheThresholdIsNotAJumpInEitherDirection() {
        assertFalse(WeightJumpPolicy.isJump(baselineKg = 90.0, candidateKg = 100.0))
        assertFalse(WeightJumpPolicy.isJump(baselineKg = 90.0, candidateKg = 80.0))
    }

    @Test
    fun aSmallerChangeIsNotAJump() {
        assertFalse(WeightJumpPolicy.isJump(baselineKg = 90.0, candidateKg = 99.9))
        assertFalse(WeightJumpPolicy.isJump(baselineKg = 90.0, candidateKg = 80.1))
    }
}
