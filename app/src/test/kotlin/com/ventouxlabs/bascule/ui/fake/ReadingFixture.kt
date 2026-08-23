package com.ventouxlabs.bascule.ui.fake

import com.ventouxlabs.bascule.data.ErrorClass
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingSource
import com.ventouxlabs.bascule.data.ReadingStatus
import java.util.UUID

/** A minimal, valid [ReadingEntity] with only the fields a given test cares about overridden. */
fun readingFixture(
    id: String = UUID.randomUUID().toString(),
    capturedAtMillis: Long = 0L,
    weightKg: Double = 70.0,
    displayUnit: String = "kg",
    status: ReadingStatus = ReadingStatus.PENDING,
    source: ReadingSource = ReadingSource.SCALE,
    retryEpochMillis: Long = capturedAtMillis,
    attemptCount: Int = 0,
    lastError: String? = null,
    lastErrorClass: ErrorClass? = null,
): ReadingEntity = ReadingEntity(
    id = id,
    capturedAtMillis = capturedAtMillis,
    scaleTimestampMillis = null,
    userIndex = null,
    weightKg = weightKg,
    displayUnit = displayUnit,
    bodyFatPct = null,
    bodyWaterPct = null,
    musclePct = null,
    boneMassKg = null,
    bmi = null,
    bmr = null,
    amr = null,
    impedanceOhms = null,
    softLeanMassKg = null,
    status = status,
    attemptCount = attemptCount,
    retryEpochMillis = retryEpochMillis,
    lastAttemptMillis = null,
    lastError = lastError,
    lastErrorClass = lastErrorClass,
    deliveredFields = emptySet(),
    contractVersionAtDelivery = null,
    remoteDuplicate = false,
    source = source,
)
