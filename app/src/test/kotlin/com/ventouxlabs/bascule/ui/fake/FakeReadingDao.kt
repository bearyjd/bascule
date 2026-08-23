package com.ventouxlabs.bascule.ui.fake

import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [ReadingDao] for JVM tests — no Room, no instrumented test needed. */
class FakeReadingDao : ReadingDao {

    private val _rows = MutableStateFlow<List<ReadingEntity>>(emptyList())
    val rows: StateFlow<List<ReadingEntity>> = _rows.asStateFlow()

    override suspend fun insert(reading: ReadingEntity) {
        _rows.value = _rows.value + reading
    }

    override suspend fun update(reading: ReadingEntity) {
        _rows.value = _rows.value.map { if (it.id == reading.id) reading else it }
    }

    override fun observeAll() = rows

    override suspend fun pending(): List<ReadingEntity> =
        _rows.value.filter { it.status == ReadingStatus.PENDING }.sortedBy { it.capturedAtMillis }

    override suspend fun dedupCandidates(source: String, fromMillis: Long, toMillis: Long): List<ReadingEntity> =
        _rows.value.filter {
            it.status != ReadingStatus.DECLINED &&
                it.source.name == source &&
                it.capturedAtMillis in fromMillis..toMillis
        }

    override suspend fun blockedAuthCount(): Int =
        _rows.value.count { it.status == ReadingStatus.BLOCKED_AUTH }
}
