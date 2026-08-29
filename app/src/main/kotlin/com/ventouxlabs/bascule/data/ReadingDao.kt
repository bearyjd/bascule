package com.ventouxlabs.bascule.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

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
}
