package com.ventouxlabs.bascule.delivery

import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.network.ContractVersion
import com.ventouxlabs.bascule.network.ReadingField

/**
 * WP-22 (`01-plan.md`), the two-clause predicate from `00-design.md` §4.4: a
 * `SENT` row is replay-eligible when both
 *
 * 1. `contract.supportedFields ∩ row.populatedFields ⊄ row.deliveredFields`, and
 * 2. `row.remoteDuplicate == false`.
 *
 * Clause 2 is not a refinement — without it a remote-duplicate row (ADR-003,
 * `deliveredFields = ∅` despite Bascule never having POSTed it, because Atlas
 * already delivered that weigh-in) would satisfy clause 1 for every field and
 * get bulk re-POSTed into Garmin as a duplicate, months after the fact, with
 * no user action to correlate it to.
 *
 * `status == SENT` is enforced explicitly, ahead of the two numbered clauses,
 * not left to fall out of them: a `DECLINED` row also has
 * `deliveredFields = ∅` and `remoteDuplicate == false`, satisfying both
 * trivially. Without this gate it would be replayed — and delivered — with
 * no user action at all, exactly what the one-tap Garmin delivery ADR-006
 * exists to prevent (the danger this predicate's own self-review named,
 * `01-plan.md`'s `onlySentRowsAreEligible`/`emptyDeliveredFieldsAloneDoesNotImplyEligible`).
 */
object ReplayEligibility {

    fun isEligible(reading: ReadingEntity, contract: ContractVersion): Boolean {
        if (reading.status != ReadingStatus.SENT) return false
        if (reading.remoteDuplicate) return false
        val relevant = contract.supportedFields intersect populatedFields(reading)
        return !reading.deliveredFields.containsAll(relevant)
    }

    /**
     * Every [ReadingField] this row actually carries a value for. `WEIGHT`
     * and `CAPTURED_AT` are unconditional — both back non-nullable columns,
     * so every row has them — the rest mirror
     * [com.ventouxlabs.bascule.network.ReadingPayloadShaper]'s own
     * null-means-absent convention for the optional body-composition fields.
     */
    private fun populatedFields(reading: ReadingEntity): Set<ReadingField> = buildSet {
        add(ReadingField.WEIGHT)
        add(ReadingField.CAPTURED_AT)
        if (reading.bodyFatPct != null) add(ReadingField.BODY_FAT_PCT)
        if (reading.bodyWaterPct != null) add(ReadingField.BODY_WATER_PCT)
        if (reading.musclePct != null) add(ReadingField.MUSCLE_PCT)
        if (reading.boneMassKg != null) add(ReadingField.BONE_MASS_KG)
        if (reading.bmi != null) add(ReadingField.BMI)
        if (reading.bmr != null) add(ReadingField.BMR)
        if (reading.amr != null) add(ReadingField.AMR)
    }
}
