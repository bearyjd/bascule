package com.ventouxlabs.bascule.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ventouxlabs.bascule.network.ReadingField

/**
 * Six statuses, not PRP §5's three (ADR-005, ADR-006).
 *
 * The delivery drain selects [PENDING] and nothing else, which is what makes the
 * wrong-user hold structural rather than a flag every future query must remember.
 */
enum class ReadingStatus {
    PENDING,
    HELD_CONFIRM,
    SENT,
    BLOCKED_AUTH,
    FAILED_PERMANENT,
    DECLINED,
}

enum class ReadingSource { SCALE, MANUAL }

enum class ErrorClass { TRANSIENT, AUTH, PERMANENT }

/**
 * The local system of record for capture (PRP §2), schema per 00-design.md §3.1.
 *
 * `bodyWaterPct`, `boneMassKg`, `bmr` and `amr` are kept as PRP §5 names them.
 * The SIG Body Composition profile reports body water as a *mass* and basal
 * metabolism in *kilojoules*, and defines no bone-mass or AMR field at all — the
 * conversions and the resulting nulls happen at the persistence boundary, not in
 * the decoder (docs/prp/02-interface-revision.md §3).
 */
/**
 * Every column here is filtered or sorted on by a hot query and none of them had
 * an index, so each drain and each History emission was a full table scan:
 * `status` gates the drain and three History banners, and `(source,
 * capturedAtMillis)` is exactly the shape of `dedupCandidates`' lookup, which
 * runs on the capture path where latency is user-visible.
 */
@Entity(
    tableName = "readings",
    indices = [
        Index(value = ["status"]),
        Index(value = ["source", "capturedAtMillis"]),
    ],
)
data class ReadingEntity(
    @PrimaryKey val id: String,
    val capturedAtMillis: Long,
    /**
     * The scale's own clock from the Weight Measurement frame, null when the
     * frame carried no timestamp. Kept alongside [capturedAtMillis] rather than
     * replacing it: dedup (00-design.md §3.3) and history sort key on the phone
     * clock, and the two are different facts — a reading the scale buffered and
     * delivered later would otherwise record its delivery time as its capture
     * time. Which of the two a v2 replay joins on is part of the A6 escalation.
     */
    val scaleTimestampMillis: Long?,
    val userIndex: Int?,
    val weightKg: Double,
    val displayUnit: String,
    val bodyFatPct: Double?,
    val bodyWaterPct: Double?,
    val musclePct: Double?,
    val boneMassKg: Double?,
    val bmi: Double?,
    val bmr: Double?,
    val amr: Double?,
    val impedanceOhms: Double?,
    val softLeanMassKg: Double?,
    val status: ReadingStatus,
    val attemptCount: Int,
    /**
     * Start of the current retriable period and the expiry anchor (ADR-005).
     * Reset to `now` on every re-entry into [ReadingStatus.PENDING], so a
     * recovered row gets a full fresh window instead of expiring instantly.
     */
    val retryEpochMillis: Long,
    val lastAttemptMillis: Long?,
    /** Sanitised — never a token and never a response body verbatim. */
    val lastError: String?,
    val lastErrorClass: ErrorClass?,
    @ColumnInfo(name = "deliveredFields") val deliveredFields: Set<ReadingField>,
    val contractVersionAtDelivery: Int?,
    val remoteDuplicate: Boolean,
    val source: ReadingSource,
    /** Stable local profile association. Null for manual and schema-v1 rows. */
    val scaleProfileId: String? = null,
    /**
     * Absolute time this row may next be submitted — 00-design.md §3.4's ladder
     * (`min(30 s * 2^(attemptCount - 1), 15 min)`) or the server's own
     * `Retry-After` when it sent one, whichever the last outcome called for.
     *
     * Materialised rather than derived from `lastAttemptMillis + ladder`, because
     * the drain query is now `LIMIT`ed: SQLite has no `pow()`, so a gate that
     * lives only in Kotlin would let a batch of not-yet-due rows at the head of
     * the capture-time ordering starve everything behind them indefinitely.
     *
     * Null means "due now" — a fresh row, or one that just re-entered PENDING.
     */
    val nextAttemptMillis: Long? = null,
)
