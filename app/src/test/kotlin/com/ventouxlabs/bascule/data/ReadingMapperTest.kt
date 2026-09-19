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
            receivedAtMillis = 5_000L,
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

    /**
     * The #28 stored-measurement path: the scale hands over a weigh-in it took
     * while no phone was present. The capture time is when the person stood on
     * the scale, but the retry window — `DeliveryCoordinator.EXPIRY_MILLIS`
     * anchored on `retryEpochMillis` — must start when the phone actually got
     * the reading, or a weigh-in delivered late would arrive already expiring.
     */
    @Test
    fun aStoredWeighInIsCapturedAtTheScalesTimeButItsRetryWindowStartsOnReceipt() {
        val scaleTime = RECEIVED_AT - 97_000L
        val measurement = scaleReadingFixture(receivedAtMillis = RECEIVED_AT, scaleTimestampMillis = scaleTime)

        val entity = ReadingMapper.map(measurement, WeightUnit.KILOGRAMS, ReadingStatus.PENDING, null, "id")

        assertEquals(scaleTime, entity.capturedAtMillis)
        assertEquals(scaleTime, entity.scaleTimestampMillis)
        assertEquals(
            "the expiry anchor is the phone's receipt, never the scale's clock",
            RECEIVED_AT,
            entity.retryEpochMillis,
        )
    }

    @Test
    fun aFrameWithNoScaleTimestampIsCapturedAtTheReceivedTime() {
        val measurement = scaleReadingFixture(receivedAtMillis = RECEIVED_AT, scaleTimestampMillis = null)

        val entity = ReadingMapper.map(measurement, WeightUnit.KILOGRAMS, ReadingStatus.PENDING, null, "id")

        assertEquals(RECEIVED_AT, entity.capturedAtMillis)
        assertNull(entity.scaleTimestampMillis)
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

    /**
     * Reproduces the measurement the factor came from: a weigh-in the scale
     * displayed as BMR 1826 / AMR 3378. Pinned against the real device numbers
     * rather than a round fixture, so a change to [ReadingMapper.ACTIVITY_FACTOR]
     * fails against what the hardware actually shows.
     */
    @Test
    fun derivesAmrFromBasalMetabolismUsingTheScalesActivityFactor() {
        val measurement = scaleReadingFixture().copy(basalMetabolismKj = ReadingMapper.KJ_PER_KCAL * 1_826.0)
        val entity = ReadingMapper.map(measurement, WeightUnit.KILOGRAMS, ReadingStatus.PENDING, null, "id")
        assertEquals(3_378.1, requireNotNull(entity.amr), 0.05)
    }

    /** No basal metabolism means no basis to derive from — an invented AMR is worse than none. */
    @Test
    fun leavesAmrNullWhenTheFrameCarriedNoBasalMetabolism() {
        val measurement = scaleReadingFixture()
        val entity = ReadingMapper.map(measurement, WeightUnit.KILOGRAMS, ReadingStatus.PENDING, null, "id")
        assertNull(entity.amr)
    }

    /** A decoded AMR, if one ever arrives, outranks the estimate. */
    @Test
    fun prefersADecodedAmrOverTheDerivedOne() {
        val measurement = scaleReadingFixture()
            .copy(basalMetabolismKj = ReadingMapper.KJ_PER_KCAL * 1_826.0, amr = 9_999.0)
        val entity = ReadingMapper.map(measurement, WeightUnit.KILOGRAMS, ReadingStatus.PENDING, null, "id")
        assertEquals(9_999.0, requireNotNull(entity.amr), 0.0001)
    }

    private companion object {
        /** A realistic epoch: the fixture's default of zero would put any real scale time outside the window. */
        const val RECEIVED_AT = 1_787_000_000_000L
    }
}
