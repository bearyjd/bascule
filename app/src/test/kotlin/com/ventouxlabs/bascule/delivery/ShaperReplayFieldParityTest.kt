package com.ventouxlabs.bascule.delivery

import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.model.ContractVersion
import com.ventouxlabs.bascule.model.ReadingField
import com.ventouxlabs.bascule.network.ReadingFixtures
import com.ventouxlabs.bascule.network.V2Shaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [V2Shaper] and [ReplayEligibility] each list the optional body-composition
 * fields separately. These tests tie the two lists together: a field added to
 * one but not the other fails here instead of silently never replaying (or
 * replaying on every pass).
 */
class ShaperReplayFieldParityTest {

    private val complete = ReadingFixtures.captured()
        .copy(status = ReadingStatus.SENT, boneMassKg = 3.1, amr = 2100.0)

    @Test
    fun shaperEmitsEveryReadingFieldForACompleteRow() {
        val shaped = V2Shaper.shape(complete, WeightUnit.KILOGRAMS).fields

        assertEquals(ReadingField.entries.toSet(), shaped)
    }

    @Test
    fun completeRowIsNotEligibleOnceEveryShapedFieldIsDelivered() {
        val delivered = V2Shaper.shape(complete, WeightUnit.KILOGRAMS).fields

        assertFalse(
            ReplayEligibility.isEligible(complete.copy(deliveredFields = delivered), ContractVersion.V2_BODY_COMP),
        )
    }

    @Test
    fun replayCountsEveryShapedFieldAsOutstandingWhenItIsMissing() {
        val shaped = V2Shaper.shape(complete, WeightUnit.KILOGRAMS).fields

        for (field in shaped) {
            val row = complete.copy(deliveredFields = shaped - field)
            assertEquals(
                "dropping $field from deliveredFields should make the row eligible",
                true,
                ReplayEligibility.isEligible(row, ContractVersion.V2_BODY_COMP),
            )
        }
    }
}
