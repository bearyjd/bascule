package com.ventouxlabs.bascule.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-double / process-lifetime implementation of [DiagnosticsCounters].
 * `ConcurrentHashMap` + `AtomicInteger` because sessions increment from a
 * `WorkManager` coroutine while `HistoryScreen`/`ConfigScreen` read on the main
 * thread — this is not itself a persistence layer, that is WP-26.
 */
class InMemoryDiagnosticsCounters : DiagnosticsCounters {
    private val counters = ConcurrentHashMap<DiagnosticsCounterKey, AtomicInteger>()

    private val _snapshot = MutableStateFlow<Map<DiagnosticsCounterKey, Int>>(emptyMap())

    override fun increment(key: DiagnosticsCounterKey): Int {
        val newValue = counters.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
        publishSnapshot()
        return newValue
    }

    override fun reset(key: DiagnosticsCounterKey) {
        counters.computeIfAbsent(key) { AtomicInteger(0) }.set(0)
        publishSnapshot()
    }

    override fun value(key: DiagnosticsCounterKey): Int =
        counters[key]?.get() ?: 0

    override fun observeAll(): StateFlow<Map<DiagnosticsCounterKey, Int>> = _snapshot.asStateFlow()

    private fun publishSnapshot() {
        _snapshot.value = counters.mapValues { it.value.get() }
    }
}
