package com.ventouxlabs.bascule.ble.fake

import com.ventouxlabs.bascule.ble.ScaleReading

/** A minimal, valid [ScaleReading] with only the fields a given test cares about overridden. */
fun scaleReadingFixture(
    weightKg: Double = 70.0,
    userIndex: Int? = 1,
    capturedAtMillis: Long = 0L,
    scaleTimestampMillis: Long? = null,
    bodyFatPct: Double? = null,
    bodyWaterMassKg: Double? = null,
    decoderId: String = "test-decoder",
): ScaleReading = ScaleReading(
    weightKg = weightKg,
    userIndex = userIndex,
    bodyFatPct = bodyFatPct,
    musclePct = null,
    muscleMassKg = null,
    fatFreeMassKg = null,
    softLeanMassKg = null,
    bodyWaterMassKg = bodyWaterMassKg,
    impedanceOhms = null,
    basalMetabolismKj = null,
    bmi = null,
    heightM = null,
    boneMassKg = null,
    amr = null,
    capturedAtMillis = capturedAtMillis,
    scaleTimestampMillis = scaleTimestampMillis,
    decoderId = decoderId,
)
