package com.ventouxlabs.bascule.delivery

import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.network.ContractVersion
import com.ventouxlabs.bascule.network.ReadingField
import com.ventouxlabs.bascule.ui.fake.readingFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP-22 (`01-plan.md`), the two-clause eligibility predicate from
 * `00-design.md` §4.4. See [ReplayEligibility]'s own KDoc for the rule and
 * the DECLINED-row danger [emptyDeliveredFieldsAloneDoesNotImplyEligible]
 * guards against by name.
 */
class ReplayEligibilityTest {

    @Test
    fun undeliveredPopulatedFieldMakesRowEligible() {
        // Delivered under V1 (weight only); body_fat_pct is populated but was
        // never sent. Upgrading to V2 makes it eligible to backfill.
        val reading = readingFixture(
            status = ReadingStatus.SENT,
            bodyFatPct = 18.4,
            deliveredFields = setOf(ReadingField.WEIGHT, ReadingField.CAPTURED_AT),
        )

        assertTrue(ReplayEligibility.isEligible(reading, ContractVersion.V2_BODY_COMP))
    }

    @Test
    fun fullyDeliveredRowIsNotEligible() {
        val reading = readingFixture(
            status = ReadingStatus.SENT,
            bodyFatPct = 18.4,
            deliveredFields = setOf(ReadingField.WEIGHT, ReadingField.CAPTURED_AT, ReadingField.BODY_FAT_PCT),
        )

        assertFalse(ReplayEligibility.isEligible(reading, ContractVersion.V2_BODY_COMP))
    }

    @Test
    fun remoteDuplicateRowIsNeverEligible() {
        // Atlas already delivered this weigh-in -- deliveredFields = ∅
        // trivially satisfies clause 1 for every field, which is exactly why
        // clause 2 exists: without it, every Atlas-won reading would be
        // bulk-re-POSTed into Garmin as a duplicate on the next upgrade.
        val reading = readingFixture(
            status = ReadingStatus.SENT,
            bodyFatPct = 18.4,
            deliveredFields = emptySet(),
            remoteDuplicate = true,
        )

        assertFalse(ReplayEligibility.isEligible(reading, ContractVersion.V2_BODY_COMP))
    }

    @Test
    fun onlySentRowsAreEligible() {
        // Otherwise-eligible in every respect (an undelivered populated
        // field, not a remote duplicate) -- only status should gate this.
        ReadingStatus.entries.forEach { status ->
            val reading = readingFixture(
                status = status,
                bodyFatPct = 18.4,
                deliveredFields = setOf(ReadingField.WEIGHT, ReadingField.CAPTURED_AT),
            )

            assertEquals(
                "status=$status",
                status == ReadingStatus.SENT,
                ReplayEligibility.isEligible(reading, ContractVersion.V2_BODY_COMP),
            )
        }
    }

    @Test
    fun emptyDeliveredFieldsAloneDoesNotImplyEligible() {
        // The self-review's named danger, called out as its own test rather
        // than left to the parameterized sweep above: a DECLINED row has
        // deliveredFields = ∅ and remoteDuplicate == false, satisfying BOTH
        // numbered clauses trivially. Without the explicit status == SENT
        // gate this would be replayed -- and delivered -- with no user
        // action at all, exactly what ADR-006's one-tap Garmin delivery
        // exists to prevent.
        val declined = readingFixture(
            status = ReadingStatus.DECLINED,
            bodyFatPct = 18.4,
            deliveredFields = emptySet(),
            remoteDuplicate = false,
        )

        assertFalse(ReplayEligibility.isEligible(declined, ContractVersion.V2_BODY_COMP))
    }
}
