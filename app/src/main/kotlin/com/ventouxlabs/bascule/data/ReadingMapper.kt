package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.ble.ScaleReading

/**
 * The persistence boundary: `ScaleReading` (raw SIG units) to [ReadingEntity]
 * (PRP §5's schema).
 *
 * This is where the two unit conversions live that
 * docs/prp/02-interface-revision.md §3 deliberately kept out of the decoder —
 * basal metabolism kJ to the `bmr` column's kcal, and body water *mass* to the
 * `bodyWaterPct` column, which needs the weight to divide by. Keeping them here
 * leaves the parsers verifiable field-for-field against the SIG specification.
 *
 * Pure field mapping only. The dedup gate (00-design.md §3.3) and the
 * user-attribution gate (§7) were specified here but live in [ReadingIngestor],
 * which owns the whole persistence boundary — it decides the [ReadingStatus]
 * this function is *given* and whether the mapped row is inserted at all. This
 * object never rejects a reading and never touches the DAO.
 */
object ReadingMapper {
    /** Body Composition Service 1.0 reports basal metabolism in kilojoules. */
    const val KJ_PER_KCAL = 4.184

    /**
     * AMR is not a field of the SIG Body Composition profile and never arrives
     * on the wire — the BF720 *derives* it, exactly as this does, by
     * multiplying basal metabolism by a coefficient tied to the activity level
     * configured on the scale.
     *
     * 1.85 is measured from this scale, not taken from a table: a weigh-in
     * displaying BMR 1826 kcal showed AMR 3378, and 3378 / 1826 = 1.84995.
     * That is one observation but a tight one. The scale displays whole kcal,
     * so the true ratio lies in 3377.5/1826.5 .. 3378.5/1825.5 — a window of
     * 1.8492..1.8507, ±0.04%, with 1.85 near its centre. Re-read on
     * 2026-09-09: the same 1826 / 3378, so nothing contradicts it.
     * It deliberately matches **no** published multiplier — Harris-Benedict puts
     * level 4 at 1.725 and level 5 at 1.9, the DGE PAL table 1.8 and 2.0, and
     * the closest of those is 91 kcal out. Implementing a textbook value would
     * have produced a confidently wrong number.
     *
     * Two limits, both accepted deliberately (user's call, 2026-09-08):
     * - **It encodes one activity level**, the one this scale is set to —
     *   reported as level 4 (2026-09-09). That is the user's recollection,
     *   not a value read back off the scale's menu, so treat it as probable
     *   rather than established. If it holds, Beurer's level-4 coefficient
     *   is its own: 1.85 against Harris-Benedict's 1.725 and the DGE's 1.8.
     *   A different level is a different coefficient, and one data point
     *   cannot recover the rest of the table.
     * - **It will read a few kcal above the scale's own display.** The scale
     *   works in kcal internally and transmits kilojoules: the wire carried
     *   7649 kJ (1828.15 kcal) for a reading the scale displayed as 1826, so
     *   this computes 3382 where the scale shows 3378. The rounding the scale
     *   applies before display is not recoverable from what it sends.
     */
    const val ACTIVITY_FACTOR = 1.85

    fun map(
        measurement: ScaleReading,
        unit: WeightUnit,
        status: ReadingStatus,
        profileId: String?,
        id: String,
    ): ReadingEntity = ReadingEntity(
        id = id,
        capturedAtMillis = measurement.capturedAtMillis,
        scaleTimestampMillis = measurement.scaleTimestampMillis,
        userIndex = measurement.userIndex,
        weightKg = measurement.weightKg,
        displayUnit = unit.wire,
        bodyFatPct = measurement.bodyFatPct,
        bodyWaterPct = measurement.bodyWaterMassKg?.let { it / measurement.weightKg * PERCENT_SCALE },
        musclePct = measurement.musclePct,
        boneMassKg = measurement.boneMassKg,
        bmi = measurement.bmi,
        bmr = measurement.basalMetabolismKj?.div(KJ_PER_KCAL),
        // Derived here rather than in the decoder because it is not decoded:
        // no frame carries it. Falls back to measurement.amr so a future
        // non-SIG decoder that *does* read one is preferred over this estimate
        // rather than silently overwritten by it.
        amr = measurement.amr ?: measurement.basalMetabolismKj?.div(KJ_PER_KCAL)?.times(ACTIVITY_FACTOR),
        impedanceOhms = measurement.impedanceOhms,
        softLeanMassKg = measurement.softLeanMassKg,
        status = status,
        attemptCount = 0,
        retryEpochMillis = measurement.capturedAtMillis,
        lastAttemptMillis = null,
        lastError = null,
        lastErrorClass = null,
        deliveredFields = emptySet(),
        contractVersionAtDelivery = null,
        remoteDuplicate = false,
        source = ReadingSource.SCALE,
        scaleProfileId = profileId,
    )

    /** Body-water arrives as an absolute mass; VitalForge stores it as a percentage of body weight. */
    private const val PERCENT_SCALE = 100.0
}
