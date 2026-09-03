package com.ventouxlabs.bascule.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// A Room @Dao is structurally a flat list of small, single-purpose queries --
// merging any of these to reach the class threshold of 20 would hide one
// query's WHERE clause inside another's, which is the actual correctness
// surface ReadingDaoSqlTest exists to pin. The interface default (11) has no
// override in detekt.yml the way the class default does; this is that
// exception, made explicit here rather than by quietly raising it globally.
@Suppress("TooManyFunctions")
@Dao
interface ReadingDao {

    @Insert
    suspend fun insert(reading: ReadingEntity)

    @Update
    suspend fun update(reading: ReadingEntity)

    @Query("SELECT * FROM readings ORDER BY capturedAtMillis DESC")
    fun observeAll(): Flow<List<ReadingEntity>>

    /**
     * The drain query selects PENDING and nothing else. HELD_CONFIRM is a
     * separate status precisely so a query that forgets it exists cannot deliver
     * an unattributed reading (ADR-006).
     *
     * The `nextAttemptMillis` clause is 00-design.md §3.4's per-row backoff gate.
     * It lives here rather than in the drainer because of [limit]: filtering in
     * Kotlin *after* the `LIMIT` would let a page of not-yet-due rows at the head
     * of the capture-time ordering starve every due row behind them. Null means
     * never attempted, or re-entered PENDING — due now either way.
     */
    @Query(
        """
        SELECT * FROM readings
        WHERE status = 'PENDING'
          AND (nextAttemptMillis IS NULL OR nextAttemptMillis <= :nowMillis)
        ORDER BY capturedAtMillis ASC
        LIMIT :limit
        """,
    )
    suspend fun pending(nowMillis: Long, limit: Int): List<ReadingEntity>

    /**
     * Dedup corpus (00-design.md §3.3): every status within the window except
     * DECLINED, which is another person's weight and must not suppress ours.
     */
    @Query(
        """
        SELECT * FROM readings
        WHERE status != 'DECLINED'
          AND source = :source
          AND capturedAtMillis BETWEEN :fromMillis AND :toMillis
        """,
    )
    suspend fun dedupCandidates(source: String, fromMillis: Long, toMillis: Long): List<ReadingEntity>

    @Query("SELECT COUNT(*) FROM readings WHERE status = 'BLOCKED_AUTH'")
    suspend fun blockedAuthCount(): Int

    /**
     * §8.6: saving a new token flips every `BLOCKED_AUTH` row back to
     * `PENDING`, on a fresh retry window — the same reset `HistoryViewModel`
     * applies to a manual retry, since a row that's been sitting blocked has
     * no more claim to its old `attemptCount`/backoff than one that's been
     * failing outright.
     */
    @Query(
        """
        UPDATE readings
        SET status = 'PENDING', attemptCount = 0, retryEpochMillis = :nowMillis,
            lastError = NULL, lastErrorClass = NULL, nextAttemptMillis = NULL
        WHERE status = 'BLOCKED_AUTH'
        """,
    )
    suspend fun unblockAuthRows(nowMillis: Long)

    @Query(
        "UPDATE readings SET status = 'BLOCKED_AUTH', lastError = 'authentication required', " +
            "lastErrorClass = 'AUTH' WHERE status = 'PENDING'",
    )
    suspend fun blockAllPendingForAuth()

    @Query("SELECT COUNT(*) FROM readings WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT MAX(capturedAtMillis) FROM readings WHERE source = 'SCALE'")
    fun observeLastScaleCapture(): Flow<Long?>

    /**
     * WP-22's candidate pool. Per-row eligibility
     * ([com.ventouxlabs.bascule.delivery.ReplayEligibility]) compares
     * `deliveredFields` (a `Set<ReadingField>` column) against the active
     * contract, which SQL can't express — so this returns every `SENT` row
     * and the filter runs in Kotlin, not here.
     */
    @Query("SELECT * FROM readings WHERE status = 'SENT'")
    suspend fun sent(): List<ReadingEntity>

    /**
     * WP-22: re-queue rows [com.ventouxlabs.bascule.delivery.ReplayEligibility]
     * found eligible. Same reset shape as [unblockAuthRows] — a fresh retry
     * window, not a continuation of whatever attempt/backoff state the
     * original `SENT` delivery left behind, since this is a new delivery
     * attempt in every sense that matters to the drain.
     */
    @Query(
        """
        UPDATE readings
        SET status = 'PENDING', attemptCount = 0, retryEpochMillis = :nowMillis,
            lastError = NULL, lastErrorClass = NULL, nextAttemptMillis = NULL
        WHERE id IN (:ids)
        """,
    )
    suspend fun requeueForReplay(ids: List<String>, nowMillis: Long)
}
