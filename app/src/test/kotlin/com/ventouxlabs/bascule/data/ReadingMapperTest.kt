package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.ble.fake.scaleReadingFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingMapperTest {

    @Test
    fun mapsCoreFieldsAndAttribution() {
        val measurement = scaleReadingFixture(
            weightKg = 71.5,
            userIndex = 2,
            capturedAtMillis = 5_000L,
        )
        val entity = ReadingMapper.map(
            measurement = measurement,
            unit = WeightUnit.KILOGRAMS,
            status = ReadingStatus.PENDING,
            profileId = "profile-1",
            id = "reading-1",
        )
        assertEquals("reading-1", entity.id)
        assertEquals(71.5, entity.weightKg, 0.0)
        assertEquals(2, entity.userIndex)
        assertEquals(5_000L, entity.capturedAtMillis)
        assertEquals("kg", entity.displayUnit)
        assertEquals(ReadingStatus.PENDING, entity.status)
        assertEquals("profile-1", entity.scaleProfileId)
        assertEquals(ReadingSource.SCALE, entity.source)
        assertEquals(0, entity.attemptCount)
        assertEquals(5_000L, entity.retryEpochMillis)
    }

    @Test
    fun convertsBasalMetabolismFromKilojoulesToKilocalories() {
        val measurement = scaleReadingFixture().copy(basalMetabolismKj = ReadingMapper.KJ_PER_KCAL * 1_500.0)
        val entity = ReadingMapper.map(measurement, WeightUnit.KILOGRAMS, ReadingStatus.PENDING, null, "id")
        assertEquals(1_500.0, requireNotNull(entity.bmr), 0.0001)
    }

    @Test
    fun derivesBodyWaterPercentFromBodyWaterMassAndWeight() {
        val measurement = scaleReadingFixture(weightKg = 80.0).copy(bodyWaterMassKg = 40.0)
        val entity = ReadingMapper.map(measurement, WeightUnit.KILOGRAMS, ReadingStatus.PENDING, null, "id")
        assertEquals(50.0, requireNotNull(entity.bodyWaterPct), 0.0001)
    }

    @Test
    fun leavesOptionalFieldsNullWhenTheMeasurementDoesNotProvideThem() {
        val measurement = scaleReadingFixture()
        val entity = ReadingMapper.map(measurement, WeightUnit.KILOGRAMS, ReadingStatus.PENDING, null, "id")
        assertNull(entity.bodyWaterPct)
        assertNull(entity.bmr)
        assertNull(entity.bodyFatPct)
        assertNull(entity.scaleProfileId)
    }
}
