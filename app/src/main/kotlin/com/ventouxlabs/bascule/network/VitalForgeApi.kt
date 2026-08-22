package com.ventouxlabs.bascule.network

import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.WeightUnit
import kotlin.time.Duration

/** Fields VitalForge can be told about (00-design.md §4.3). */
enum class ReadingField {
    WEIGHT,
    BODY_FAT_PCT,
    BODY_WATER_PCT,
    MUSCLE_PCT,
    BONE_MASS_KG,
    BMI,
    BMR,
    AMR,
    CAPTURED_AT,
}

/**
 * Contract version is configured, never sniffed: probing v2 against a v1 server
 * would burn a real reading on an endpoint that might partially accept it
 * (00-design.md §4.3).
 */
enum class ContractVersion(val wire: Int, val supportedFields: Set<ReadingField>) {
    V1_WEIGHT_ONLY(1, setOf(ReadingField.WEIGHT)),
    V2_BODY_COMP(2, ReadingField.entries.toSet()),
}

/**
 * The single versioned interface. Upgrading to v2 is a shaper swap plus a
 * [ContractVersion] change — no call site moves.
 */
interface VitalForgeApi {
    val contract: ContractVersion

    suspend fun submitReading(reading: ReadingEntity, unit: WeightUnit): SubmitResult

    /** ADR-003 contention check. Absent on servers that do not expose it. */
    suspend fun recentReadings(within: Duration): RecentResult
}

sealed interface SubmitResult {
    /** [deliveredFields] comes from the shaper that actually ran, so it cannot drift from the wire. */
    data class Accepted(val deliveredFields: Set<ReadingField>) : SubmitResult
    data class TransientFailure(val reason: String, val retryAfter: Duration?) : SubmitResult
    data class AuthRejected(val httpCode: Int) : SubmitResult
    data class PermanentRejection(val httpCode: Int, val reason: String) : SubmitResult
}

sealed interface RecentResult {
    data class Readings(val readings: List<RemoteReading>) : RecentResult

    /** The endpoint is absent or the call failed. Callers post anyway (ADR-003 step 3). */
    data class Unavailable(val reason: String) : RecentResult
}

data class RemoteReading(val weightKg: Double, val capturedAtMillis: Long)
