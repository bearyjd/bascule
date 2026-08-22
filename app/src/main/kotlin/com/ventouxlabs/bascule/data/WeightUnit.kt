package com.ventouxlabs.bascule.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Display unit. Storage is always kilograms (00-design.md §2.7) — storing
 * display units would make the dedup tolerance unit-dependent and corrupt
 * history the first time the scale's unit switch is flipped.
 */
enum class WeightUnit(val wire: String) {
    KILOGRAMS("kg"),
    POUNDS("lbs"),
    ;

    fun fromKilograms(kilograms: Double): Double {
        val value = when (this) {
            KILOGRAMS -> kilograms
            POUNDS -> kilograms / KG_PER_LB
        }
        return BigDecimal(value).setScale(WIRE_SCALE, RoundingMode.HALF_UP).toDouble()
    }

    fun toKilograms(value: Double): Double = when (this) {
        KILOGRAMS -> value
        POUNDS -> value * KG_PER_LB
    }

    companion object {
        /** Exact by definition (international avoirdupois pound). */
        const val KG_PER_LB = 0.45359237

        /** VitalForge stores one decimal more than any scale resolves. */
        const val WIRE_SCALE = 2
    }
}
