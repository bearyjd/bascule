package com.ventouxlabs.bascule.ble.decoders

/**
 * A decoded Weight Measurement (0x2A9D).
 *
 * [rawWeight] is retained alongside [weightKg] because frame identity for
 * duplicate suppression is `(userIndex, timestamp, raw weight)` — comparing
 * scaled doubles would make the rule depend on floating-point equality.
 */
internal data class WeightMeasurement(
    val weightKg: Double,
    val rawWeight: Int,
    val timestampMillis: Long?,
    val userIndex: Int?,
    val bmi: Double?,
    val heightM: Double?,
)

/**
 * Weight Measurement characteristic format, Bluetooth SIG Weight Scale Service
 * 1.0 §3.1: flags uint8, then the present fields in fixed order — weight
 * (mandatory), time stamp, user ID, BMI, height.
 *
 * Flags: bit 0 unit (0 = SI kg/m, 1 = Imperial lb/inch), bit 1 time stamp
 * present, bit 2 user ID present, bit 3 BMI and height present.
 *
 * Cross-checked against openScale's `StandardWeightProfileHandler.kt`
 * (GPL-3.0); reimplemented from the specification, no source copied (ADR-002).
 *
 * Verified against the live BF720 capture in docs/prp/03-hardware-validation.md:
 * `0e f4 46 ea 07 08 16 10 33 01 02 3a 01 a4 06` decodes to 90.82 kg,
 * 2026-08-22 16:51:01, user 2, BMI 31.4, height 1.700 m.
 */
internal object WeightMeasurementParser {

    private const val FLAG_IMPERIAL = 0
    private const val FLAG_TIMESTAMP = 1
    private const val FLAG_USER_ID = 2
    private const val FLAG_BMI_AND_HEIGHT = 3

    /** Flags byte plus the mandatory weight field. */
    const val MIN_LENGTH = 3

    /** Exact by definition (international avoirdupois pound). */
    const val KG_PER_LB = 0.45359237
    const val METRES_PER_INCH = 0.0254

    fun parse(bytes: ByteArray): WeightMeasurement? {
        val reader = FrameReader(bytes)
        val flags = reader.u8() ?: return null
        val imperial = flags.hasBit(FLAG_IMPERIAL)

        val rawWeight = reader.u16() ?: return null
        val weightKg = if (imperial) {
            rawWeight * SigWeightProfile.WEIGHT_LB_PER_LSB * KG_PER_LB
        } else {
            rawWeight * SigWeightProfile.WEIGHT_KG_PER_LSB
        }

        val timestamp = if (flags.hasBit(FLAG_TIMESTAMP)) reader.dateTimeMillis() else null
        val userIndex = if (flags.hasBit(FLAG_USER_ID)) reader.u8() else null

        var bmi: Double? = null
        var heightM: Double? = null
        if (flags.hasBit(FLAG_BMI_AND_HEIGHT)) {
            bmi = reader.u16()?.times(SigWeightProfile.BMI_PER_LSB)
            heightM = reader.u16()?.let { raw ->
                if (imperial) {
                    raw * SigWeightProfile.HEIGHT_INCHES_PER_LSB * METRES_PER_INCH
                } else {
                    raw * SigWeightProfile.HEIGHT_METRES_PER_LSB
                }
            }
        }

        if (reader.underrun) return null

        return WeightMeasurement(
            weightKg = weightKg,
            rawWeight = rawWeight,
            timestampMillis = timestamp,
            userIndex = userIndex,
            bmi = bmi,
            heightM = heightM,
        )
    }
}
