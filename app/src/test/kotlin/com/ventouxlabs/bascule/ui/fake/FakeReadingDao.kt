package com.ventouxlabs.bascule.ui.fake

import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [ReadingDao] for JVM tests — no Room, no instrumented test needed. */
class FakeReadingDao(
    /**
     * Invoked before the row is added, and awaited — a real seam for a test
     * that needs a genuine suspension point mid-`insert`, since every method
     * here otherwise completes without ever yielding. Defaults to a no-op so
     * every existing caller is unaffected.
     */
    private val onInsert: suspend () -> Unit = {},
) : ReadingDao {

    private val _rows = MutableStateFlow<List<ReadingEntity>>(emptyList())
    val rows: StateFlow<List<ReadingEntity>> = _rows.asStateFlow()

    override suspend fun insert(reading: ReadingEntity) {
        onInsert()
        _rows.value = _rows.value + reading
    }

    override suspend fun update(reading: ReadingEntity) {
        _rows.value = _rows.value.map { if (it.id == reading.id) reading else it }
    }

    override fun observeAll() = rows

    /** Mirrors the live `@Query` clause for clause: status, the §3.4 due-gate, ordering, then the LIMIT. */
    override suspend fun pending(nowMillis: Long, limit: Int): List<ReadingEntity> =
        _rows.value
            .filter { it.status == ReadingStatus.PENDING }
            .filter { it.nextAttemptMillis == null || it.nextAttemptMillis <= nowMillis }
            .sortedBy { it.capturedAtMillis }
            .take(limit)

    override suspend fun dedupCandidates(source: String, fromMillis: Long, toMillis: Long): List<ReadingEntity> =
        _rows.value.filter {
            it.status != ReadingStatus.DECLINED &&
                it.source.name == source &&
                it.capturedAtMillis in fromMillis..toMillis
        }

    override suspend fun blockedAuthCount(): Int =
        _rows.value.count { it.status == ReadingStatus.BLOCKED_AUTH }

    override suspend fun unblockAuthRows(nowMillis: Long) {
        _rows.value = _rows.value.map { reading ->
            if (reading.status != ReadingStatus.BLOCKED_AUTH) {
                reading
            } else {
                reading.copy(
                    status = ReadingStatus.PENDING,
                    attemptCount = 0,
                    retryEpochMillis = nowMillis,
                    lastError = null,
                    lastErrorClass = null,
                    nextAttemptMillis = null,
                )
            }
        }
    }

    override suspend fun blockAllPendingForAuth() {
        _rows.value = _rows.value.map {
            if (it.status == ReadingStatus.PENDING) it.copy(status = ReadingStatus.BLOCKED_AUTH) else it
        }
    }

    override fun observePendingCount() = rows.map { list -> list.count { it.status == ReadingStatus.PENDING } }

    override fun observeLastScaleCapture() = rows.map { list ->
        list.filter { it.source.name == "SCALE" }.maxOfOrNull { it.capturedAtMillis }
    }

    override suspend fun sent(): List<ReadingEntity> = _rows.value.filter { it.status == ReadingStatus.SENT }

    override suspend fun requeueForReplay(ids: List<String>, nowMillis: Long) {
        val idSet = ids.toSet()
        _rows.value = _rows.value.map { reading ->
            if (reading.id !in idSet) {
                reading
            } else {
                reading.copy(
                    status = ReadingStatus.PENDING,
                    attemptCount = 0,
                    retryEpochMillis = nowMillis,
                    lastError = null,
                    lastErrorClass = null,
                    nextAttemptMillis = null,
                )
            }
        }
    }
}
