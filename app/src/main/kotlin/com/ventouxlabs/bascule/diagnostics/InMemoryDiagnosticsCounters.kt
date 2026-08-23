package com.ventouxlabs.bascule.diagnostics

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

    override fun increment(key: DiagnosticsCounterKey): Int =
        counters.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()

    override fun reset(key: DiagnosticsCounterKey) {
        counters.computeIfAbsent(key) { AtomicInteger(0) }.set(0)
    }

    override fun value(key: DiagnosticsCounterKey): Int =
        counters[key]?.get() ?: 0
}
