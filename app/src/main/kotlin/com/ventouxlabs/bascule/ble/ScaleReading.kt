package com.ventouxlabs.bascule.ble

/**
 * One complete, attributable weigh-in.
 *
 * Canonical unit is kilograms everywhere (00-design.md §2.7). There is no
 * `isStable` field: an unstable reading is never constructed.
 *
 * Revised in Phase 2 (docs/prp/02-interface-revision.md §3) against the real
 * Bluetooth SIG Body Composition Measurement field set. Fields the SIG profile
 * does not define ([boneMassKg], [amr]) are retained because PRP §5 pins the
 * persistence schema and a future non-SIG decoder may supply them; the BF720
 * never populates them.
 */
data class ScaleReading(
    val weightKg: Double,
    val userIndex: Int?,
    val bodyFatPct: Double?,
    val musclePct: Double?,
    val muscleMassKg: Double?,
    val fatFreeMassKg: Double?,
    val softLeanMassKg: Double?,
    val bodyWaterMassKg: Double?,
    val impedanceOhms: Double?,
    val basalMetabolismKj: Double?,
    val bmi: Double?,
    val heightM: Double?,
    val boneMassKg: Double?,
    val amr: Double?,
    /** Device clock when the session emitted this reading. */
    val capturedAtMillis: Long,
    /** The scale's own timestamp from the Weight Measurement frame, when present. */
    val scaleTimestampMillis: Long?,
    val decoderId: String,
)
