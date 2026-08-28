package com.ventouxlabs.bascule.ble.decoders

/**
 * A decoded Body Composition Measurement (0x2A9C).
 *
 * [timestampMillis] and [userIndex] are null on every frame the BF720 sends:
 * its flags word declares neither field present, which is why a frame of this
 * kind is not a complete reading on its own (ADR-007).
 */
internal data class BodyCompositionMeasurement(
    /**
     * Null when the scale reported the SIG "value unknown" sentinel — the BIA
     * impedance pass failed (socks, poor foot contact, too short a stand). The
     * frame is still structurally valid and its other fields still usable.
     */
    val bodyFatPct: Double?,
    val timestampMillis: Long?,
    val userIndex: Int?,
    val basalMetabolismKj: Double?,
    val musclePct: Double?,
    val muscleMassKg: Double?,
    val fatFreeMassKg: Double?,
    val softLeanMassKg: Double?,
    val bodyWaterMassKg: Double?,
    val impedanceOhms: Double?,
    val weightKg: Double?,
    val heightM: Double?,
)

/**
 * Body Composition Measurement characteristic format, Bluetooth SIG Body
 * Composition Service 1.0 §3.2: flags uint16, then Body Fat Percentage
 * (mandatory), then every flagged field in the fixed specification order —
 * time stamp, user ID, basal metabolism, muscle percentage, muscle mass, fat
 * free mass, soft lean mass, body water mass, impedance, weight, height.
 *
 * Cross-checked against openScale's `StandardWeightProfileHandler.kt`
 * (GPL-3.0); reimplemented from the specification, no source copied (ADR-002).
 *
 * Verified against the live BF720 capture in docs/prp/03-hardware-validation.md:
 * `98 03 a6 01 7a 1a 30 01 58 26 e0 1c 12 11` decodes to 42.2% fat, 6778 kJ,
 * 30.4% muscle, 49.08 kg soft lean mass, 36.96 kg body water, 437.0 ohm.
 */
internal object BodyCompositionMeasurementParser {

    private const val FLAG_IMPERIAL = 0
    private const val FLAG_TIMESTAMP = 1
    private const val FLAG_USER_ID = 2
    private const val FLAG_BASAL_METABOLISM = 3
    private const val FLAG_MUSCLE_PERCENTAGE = 4
    private const val FLAG_MUSCLE_MASS = 5
    private const val FLAG_FAT_FREE_MASS = 6
    private const val FLAG_SOFT_LEAN_MASS = 7
    private const val FLAG_BODY_WATER_MASS = 8
    private const val FLAG_IMPEDANCE = 9
    private const val FLAG_WEIGHT = 10
    private const val FLAG_HEIGHT = 11

    /** Flags word plus the mandatory body-fat field. */
    const val MIN_LENGTH = 4

    fun parse(bytes: ByteArray): BodyCompositionMeasurement? {
        val reader = FrameReader(bytes)
        val flags = reader.u16() ?: return null
        val imperial = flags.hasBit(FLAG_IMPERIAL)
        val massPerLsb = if (imperial) {
            SigWeightProfile.MASS_LB_PER_LSB
        } else {
            SigWeightProfile.MASS_KG_PER_LSB
        }
        val heightPerLsb = if (imperial) {
            SigWeightProfile.HEIGHT_INCHES_PER_LSB * WeightMeasurementParser.METRES_PER_INCH
        } else {
            SigWeightProfile.HEIGHT_METRES_PER_LSB
        }
        val massToKg = if (imperial) WeightMeasurementParser.KG_PER_LB else 1.0

        val bodyFat = reader.u16()?.takeIf { it != WeightMeasurementParser.VALUE_UNKNOWN }
        val timestamp = if (flags.hasBit(FLAG_TIMESTAMP)) reader.dateTimeMillis() else null
        val userIndex = if (flags.hasBit(FLAG_USER_ID)) reader.u8() else null

        val measurement = BodyCompositionMeasurement(
            bodyFatPct = bodyFat?.times(SigWeightProfile.PERCENT_PER_LSB),
            timestampMillis = timestamp,
            userIndex = userIndex,
            basalMetabolismKj = reader.field(flags, FLAG_BASAL_METABOLISM) { it.toDouble() },
            musclePct = reader.field(flags, FLAG_MUSCLE_PERCENTAGE) {
                it * SigWeightProfile.PERCENT_PER_LSB
            },
            muscleMassKg = reader.field(flags, FLAG_MUSCLE_MASS) { it * massPerLsb * massToKg },
            fatFreeMassKg = reader.field(flags, FLAG_FAT_FREE_MASS) { it * massPerLsb * massToKg },
            softLeanMassKg = reader.field(flags, FLAG_SOFT_LEAN_MASS) { it * massPerLsb * massToKg },
            bodyWaterMassKg = reader.field(flags, FLAG_BODY_WATER_MASS) { it * massPerLsb * massToKg },
            impedanceOhms = reader.field(flags, FLAG_IMPEDANCE) {
                it * SigWeightProfile.IMPEDANCE_OHMS_PER_LSB
            },
            weightKg = reader.field(flags, FLAG_WEIGHT) { it * massPerLsb * massToKg },
            heightM = reader.field(flags, FLAG_HEIGHT) { it * heightPerLsb },
        )

        return if (reader.underrun) null else measurement
    }

    /**
     * A flagged-but-unknown field must still be *read* — the sentinel occupies
     * its two bytes on the wire, and skipping the read would shift every
     * following field's offset and misparse the rest of the frame silently.
     */
    private inline fun FrameReader.field(flags: Int, bit: Int, scale: (Int) -> Double): Double? {
        if (!flags.hasBit(bit)) return null
        val raw = u16() ?: return null
        if (raw == WeightMeasurementParser.VALUE_UNKNOWN) return null
        return scale(raw)
    }
}
