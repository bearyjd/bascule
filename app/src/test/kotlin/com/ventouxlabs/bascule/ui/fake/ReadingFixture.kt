package com.ventouxlabs.bascule.ui.fake

import com.ventouxlabs.bascule.data.ErrorClass
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingSource
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.network.ReadingField
import java.util.UUID

/** A minimal, valid [ReadingEntity] with only the fields a given test cares about overridden. */
// Mirrors ReadingEntity's own constructor, which detekt.yml already accepts
// as a wide type by design (00-design.md §3.1) rather than adding a builder
// indirection -- this wrapper inherits the same shape, one parameter per
// field it lets a test override.
@Suppress("LongParameterList")
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
    nextAttemptMillis: Long? = null,
    bodyFatPct: Double? = null,
    bodyWaterPct: Double? = null,
    musclePct: Double? = null,
    boneMassKg: Double? = null,
    bmi: Double? = null,
    bmr: Double? = null,
    amr: Double? = null,
    deliveredFields: Set<ReadingField> = emptySet(),
    remoteDuplicate: Boolean = false,
): ReadingEntity = ReadingEntity(
    id = id,
    capturedAtMillis = capturedAtMillis,
    scaleTimestampMillis = null,
    userIndex = null,
    weightKg = weightKg,
    displayUnit = displayUnit,
    bodyFatPct = bodyFatPct,
    bodyWaterPct = bodyWaterPct,
    musclePct = musclePct,
    boneMassKg = boneMassKg,
    bmi = bmi,
    bmr = bmr,
    amr = amr,
    impedanceOhms = null,
    softLeanMassKg = null,
    status = status,
    attemptCount = attemptCount,
    retryEpochMillis = retryEpochMillis,
    lastAttemptMillis = null,
    lastError = lastError,
    lastErrorClass = lastErrorClass,
    deliveredFields = deliveredFields,
    contractVersionAtDelivery = null,
    remoteDuplicate = remoteDuplicate,
    source = source,
    nextAttemptMillis = nextAttemptMillis,
)
