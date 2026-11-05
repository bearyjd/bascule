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

    /** 0.1 kg resolution: the raw double difference of these is 10.000000000000004..7, not 10.0. */
    @Test
    fun aTrueTenKgChangeAtTenthKgResolutionIsNotAJumpDespiteFloatingPointNoise() {
        listOf(30.2 to 40.2, 54.4 to 64.4, 60.4 to 70.4, 61.9 to 71.9).forEach { (low, high) ->
            assertFalse("$low -> $high", WeightJumpPolicy.isJump(baselineKg = low, candidateKg = high))
            assertFalse("$high -> $low", WeightJumpPolicy.isJump(baselineKg = high, candidateKg = low))
        }
    }

    @Test
    fun aTenthOfAKilogramBeyondTenIsStillAJump() {
        assertTrue(WeightJumpPolicy.isJump(baselineKg = 70.3, candidateKg = 80.4))
        assertTrue(WeightJumpPolicy.isJump(baselineKg = 80.4, candidateKg = 70.3))
    }

    @Test
    fun aSmallerChangeIsNotAJump() {
        assertFalse(WeightJumpPolicy.isJump(baselineKg = 90.0, candidateKg = 99.9))
        assertFalse(WeightJumpPolicy.isJump(baselineKg = 90.0, candidateKg = 80.1))
    }
}
