package com.ventouxlabs.bascule.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryDiagnosticsCountersTest {

    @Test
    fun incrementIsAdditivePerKey() {
        val counters = InMemoryDiagnosticsCounters()

        counters.increment(DiagnosticsCounterKey.INCOMPATIBLE_STREAK)
        counters.increment(DiagnosticsCounterKey.INCOMPATIBLE_STREAK)
        counters.increment(DiagnosticsCounterKey.MISSED_QUOTA)

        assertEquals(2, counters.value(DiagnosticsCounterKey.INCOMPATIBLE_STREAK))
        assertEquals(1, counters.value(DiagnosticsCounterKey.MISSED_QUOTA))
        assertEquals(0, counters.value(DiagnosticsCounterKey.MALFORMED_COUNT))
    }

    @Test
    fun incrementReturnsTheNewValue() {
        val counters = InMemoryDiagnosticsCounters()

        assertEquals(1, counters.increment(DiagnosticsCounterKey.NO_MEASUREMENT))
        assertEquals(2, counters.increment(DiagnosticsCounterKey.NO_MEASUREMENT))
    }

    @Test
    fun resetZeroesAStreakWithoutAffectingOtherKeys() {
        val counters = InMemoryDiagnosticsCounters()
        counters.increment(DiagnosticsCounterKey.INCOMPATIBLE_STREAK)
        counters.increment(DiagnosticsCounterKey.INCOMPATIBLE_STREAK)
        counters.increment(DiagnosticsCounterKey.MISSED_QUOTA)

        counters.reset(DiagnosticsCounterKey.INCOMPATIBLE_STREAK)

        assertEquals(0, counters.value(DiagnosticsCounterKey.INCOMPATIBLE_STREAK))
        assertEquals(1, counters.value(DiagnosticsCounterKey.MISSED_QUOTA))
    }
}
