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
        amr = measurement.amr,
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
