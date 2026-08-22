package com.ventouxlabs.bascule.network

import com.ventouxlabs.bascule.data.ErrorClass
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingSource
import com.ventouxlabs.bascule.data.ReadingStatus

internal object ReadingFixtures {

    /** The captured BF720 weigh-in, as it would sit in Room. */
    const val CAPTURED_AT_MILLIS = 1_787_000_000_000L

    fun captured(
        id: String = "b1f0a4c2-0000-4000-8000-000000000001",
        weightKg: Double = 90.82,
        capturedAtMillis: Long = CAPTURED_AT_MILLIS,
        status: ReadingStatus = ReadingStatus.PENDING,
        source: ReadingSource = ReadingSource.SCALE,
    ) = ReadingEntity(
        id = id,
        capturedAtMillis = capturedAtMillis,
        scaleTimestampMillis = capturedAtMillis - 2_000L,
        userIndex = 2,
        weightKg = weightKg,
        displayUnit = "kg",
        bodyFatPct = 42.2,
        bodyWaterPct = 40.7,
        musclePct = 30.4,
        boneMassKg = null,
        bmi = 31.4,
        bmr = 1620.0,
        amr = null,
        impedanceOhms = 437.0,
        softLeanMassKg = 49.08,
        status = status,
        attemptCount = 0,
        retryEpochMillis = capturedAtMillis,
        lastAttemptMillis = null,
        lastError = null,
        lastErrorClass = null as ErrorClass?,
        deliveredFields = emptySet(),
        contractVersionAtDelivery = null,
        remoteDuplicate = false,
        source = source,
    )
}
