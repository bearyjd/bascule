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

        // A bathroom scale's plausible human range, generous on both ends
        // rather than tuned to any one body type. Kilograms is the boundary
        // that matters for storage; per-unit bounds are derived from it so
        // both display units reject the same physical range, not the same
        // raw number. Lives here rather than on either caller so the manual
        // entry path and the BLE ingest path cannot drift apart.
        const val MIN_PLAUSIBLE_WEIGHT_KG = 20.0
        const val MAX_PLAUSIBLE_WEIGHT_KG = 300.0

        val PLAUSIBLE_WEIGHT_KG_RANGE = MIN_PLAUSIBLE_WEIGHT_KG..MAX_PLAUSIBLE_WEIGHT_KG
    }
}
