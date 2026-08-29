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
 * Outcome of parsing a Weight Measurement frame.
 *
 * [Unsuccessful] is separate from [Malformed] because the SIG `0xFFFF` weight
 * means the *scale* reported no successful measurement — the frame itself is
 * well-formed. Collapsing the two would charge a working scale's "I couldn't
 * weigh you" to the malformed-frame counter, and a session that saw only such
 * frames would be diagnosed as a decode failure rather than as the
 * no-measurement it is.
 */
internal sealed interface WeightParseResult {
    data class Parsed(val measurement: WeightMeasurement) : WeightParseResult

    /** SIG "measurement unsuccessful" sentinel in the mandatory weight field. */
    data object Unsuccessful : WeightParseResult

    /** Truncated for the fields its own flags declare present. */
    data object Malformed : WeightParseResult
}

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

    /**
     * Bluetooth SIG "value unknown / measurement unsuccessful" for a uint16
     * measurement field (Weight Scale Service 1.0, Body Composition Service
     * 1.0). Scaling it as a number yields 327.675 kg or 6553.5 %, which is why
     * every uint16 field is checked for it before it is scaled.
     */
    const val VALUE_UNKNOWN = 0xFFFF

    fun parse(bytes: ByteArray): WeightParseResult {
        val reader = FrameReader(bytes)
        val flags = reader.u8() ?: return WeightParseResult.Malformed
        val imperial = flags.hasBit(FLAG_IMPERIAL)

        // The weight field is mandatory, so an unsuccessful measurement leaves
        // nothing to correlate — the whole frame is unusable, not just a field.
        val rawWeight = reader.u16() ?: return WeightParseResult.Malformed
        if (rawWeight == VALUE_UNKNOWN) return WeightParseResult.Unsuccessful
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
            // Read unconditionally, then discard the sentinel: skipping the read
            // would shift every following field by two bytes.
            bmi = reader.u16()?.takeIf { it != VALUE_UNKNOWN }?.times(SigWeightProfile.BMI_PER_LSB)
            heightM = reader.u16()?.takeIf { it != VALUE_UNKNOWN }?.let { raw ->
                if (imperial) {
                    raw * SigWeightProfile.HEIGHT_INCHES_PER_LSB * METRES_PER_INCH
                } else {
                    raw * SigWeightProfile.HEIGHT_METRES_PER_LSB
                }
            }
        }

        if (reader.underrun) return WeightParseResult.Malformed

        return WeightParseResult.Parsed(
            WeightMeasurement(
                weightKg = weightKg,
                rawWeight = rawWeight,
                timestampMillis = timestamp,
                userIndex = userIndex,
                bmi = bmi,
                heightM = heightM,
            ),
        )
    }
}
