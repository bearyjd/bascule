package com.ventouxlabs.bascule.data

/**
 * Plausibility bounds for the body-composition columns, the counterpart to
 * [WeightUnit.PLAUSIBLE_WEIGHT_KG_RANGE].
 *
 * The decoders reject the exact SIG `0xFFFF` "value unknown" sentinel, but only
 * that one value. A raw word one below it scales to thousands of percent body
 * fat or tens of thousands of kilocalories — numbers no gate downstream would
 * have questioned before they reached VitalForge.
 *
 * Bounds are deliberately generous rather than clinical: the job is to catch
 * values no human body can produce, not to second-guess a bioimpedance
 * estimate.
 */
object BodyCompositionPlausibility {

    val BODY_FAT_PCT_RANGE = 2.0..80.0
    val MUSCLE_PCT_RANGE = 5.0..90.0
    val BODY_WATER_PCT_RANGE = 10.0..90.0
    val BMI_RANGE = 8.0..100.0

    /** Basal metabolic rate in kilocalories per day, as the `bmr` column stores it. */
    val BMR_KCAL_RANGE = 300.0..8000.0

    /** The name of the first out-of-range field, or null when every field is usable. */
    fun implausibleField(reading: ReadingEntity): String? = when {
        reading.bodyFatPct.isOutside(BODY_FAT_PCT_RANGE) -> "bodyFatPct"
        reading.musclePct.isOutside(MUSCLE_PCT_RANGE) -> "musclePct"
        reading.bodyWaterPct.isOutside(BODY_WATER_PCT_RANGE) -> "bodyWaterPct"
        reading.bmi.isOutside(BMI_RANGE) -> "bmi"
        reading.bmr.isOutside(BMR_KCAL_RANGE) -> "bmr"
        else -> null
    }

    /** An absent field is not implausible; a non-finite one always is. */
    private fun Double?.isOutside(range: ClosedFloatingPointRange<Double>): Boolean {
        val value = this ?: return false
        return !value.isFinite() || value !in range
    }
}
