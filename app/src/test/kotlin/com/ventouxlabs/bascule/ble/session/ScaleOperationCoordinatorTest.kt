package com.ventouxlabs.bascule.ble.session

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleOperationCoordinatorTest {
    @Test
    fun administrativeAndMeasurementOperationsNeverOverlap() = runTest {
        val coordinator = ScaleOperationCoordinator()
        val inside = AtomicInteger()
        val peak = AtomicInteger()
        suspend fun enter() {
            val current = inside.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            delay(10)
            inside.decrementAndGet()
        }
        val measurement = async { coordinator.withScale(ScaleSessionPurpose.MEASUREMENT) { enter() } }
        val administration = async { coordinator.withScale(ScaleSessionPurpose.ADMINISTRATION) { enter() } }
        measurement.await()
        administration.await()
        assertEquals(1, peak.get())
    }
}
