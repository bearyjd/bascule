package com.ventouxlabs.bascule.data

import com.ventouxlabs.bascule.ble.fake.scaleReadingFixture
import com.ventouxlabs.bascule.data.fake.FakeScaleProfileStore
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingIngestorTest {

    private val deviceAddress = "AA:BB:CC:DD:EE:FF"

    private fun profile(id: String, scaleIndex: Int, active: Boolean) = ScaleProfile(
        id = id,
        deviceAddress = deviceAddress,
        scaleIndex = scaleIndex,
        consentCode = 1234,
        label = "Profile $scaleIndex",
        registeredAtMillis = 0L,
        active = active,
    )

    private fun ingestor(dao: FakeReadingDao, profiles: FakeScaleProfileStore) =
        ReadingIngestor(dao, profiles, unitProvider = { WeightUnit.KILOGRAMS }, idProvider = { "fixed-id" })

    @Test
    fun implausibleWeightIsRejectedAndNeverPersisted() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        val result = ingestor(dao, profiles).ingest(deviceAddress, scaleReadingFixture(weightKg = 5.0, userIndex = 1))
        assertTrue(result is IngestResult.Rejected)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun nonFiniteWeightIsRejected() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        val reading = scaleReadingFixture(weightKg = Double.NaN, userIndex = 1)
        val result = ingestor(dao, profiles).ingest(deviceAddress, reading)
        assertTrue(result is IngestResult.Rejected)
    }

    /**
     * The decoders reject only the exact `0xFFFF` SIG sentinel, so a raw word
     * one below it reaches here as thousands of percent body fat. Nothing
     * downstream would have questioned it.
     */
    @Test
    fun aSentinelAdjacentBodyFatIsRejectedAndNeverPersisted() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        val reading = scaleReadingFixture(weightKg = 71.0, userIndex = 1, bodyFatPct = NEAR_SENTINEL_PCT)

        val result = ingestor(dao, profiles).ingest(deviceAddress, reading)

        assertTrue(result is IngestResult.Rejected)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun anImplausibleBasalMetabolismIsRejected() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        // 65 534 × 4.184 kcal is tens of thousands of kilocalories a day.
        val reading = scaleReadingFixture(weightKg = 71.0, userIndex = 1)
            .copy(basalMetabolismKj = NEAR_SENTINEL_RAW * ReadingMapper.KJ_PER_KCAL)

        assertTrue(ingestor(dao, profiles).ingest(deviceAddress, reading) is IngestResult.Rejected)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun anImplausibleBodyWaterPercentageIsRejected() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        // Body water is stored as a percentage of weight — a mass above the
        // body's own is not a measurement.
        val reading = scaleReadingFixture(weightKg = 71.0, userIndex = 1, bodyWaterMassKg = 327.67)

        assertTrue(ingestor(dao, profiles).ingest(deviceAddress, reading) is IngestResult.Rejected)
    }

    @Test
    fun anImplausibleBmiOrMusclePercentageIsRejected() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        val instance = ingestor(dao, profiles)
        val base = scaleReadingFixture(weightKg = 71.0, userIndex = 1)

        assertTrue(
            instance.ingest(deviceAddress, base.copy(bmi = NEAR_SENTINEL_PCT)) is IngestResult.Rejected,
        )
        assertTrue(
            instance.ingest(deviceAddress, base.copy(musclePct = NEAR_SENTINEL_PCT)) is IngestResult.Rejected,
        )
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun plausibleBodyCompositionFieldsAreStillPersisted() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        val reading = scaleReadingFixture(weightKg = 90.82, userIndex = 1, bodyFatPct = 42.2, bodyWaterMassKg = 36.96)
            .copy(musclePct = 30.4, bmi = 31.4, basalMetabolismKj = 6_778.0)

        assertTrue(ingestor(dao, profiles).ingest(deviceAddress, reading) is IngestResult.Inserted)
    }

    @Test
    fun aReadingFromTheActiveProfileIsInsertedAsPending() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        val result = ingestor(dao, profiles).ingest(deviceAddress, scaleReadingFixture(weightKg = 71.0, userIndex = 1))
        assertTrue(result is IngestResult.Inserted)
        val row = (result as IngestResult.Inserted).reading
        assertEquals(ReadingStatus.PENDING, row.status)
        assertEquals("p1", row.scaleProfileId)
        assertEquals(ReadingStatus.PENDING, dao.rows.value.single().status)
    }

    @Test
    fun aReadingFromARegisteredButNonActiveProfileIsHeldNotUploaded() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(
            listOf(profile("active-profile", 1, active = true), profile("other-profile", 2, active = false)),
        )
        val result = ingestor(dao, profiles).ingest(deviceAddress, scaleReadingFixture(weightKg = 71.0, userIndex = 2))
        assertTrue(result is IngestResult.Held)
        val row = (result as IngestResult.Held).reading
        assertEquals(ReadingStatus.HELD_CONFIRM, row.status)
        assertEquals("other-profile", row.scaleProfileId)
    }

    @Test
    fun aReadingWithNoMatchingRegisteredProfileIsHeldWithNoAttribution() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("active-profile", 1, active = true)))
        val result = ingestor(dao, profiles).ingest(deviceAddress, scaleReadingFixture(weightKg = 71.0, userIndex = 9))
        assertTrue(result is IngestResult.Held)
        assertNull((result as IngestResult.Held).reading.scaleProfileId)
    }

    @Test
    fun aReadingWithNoUserIndexIsHeld() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("active-profile", 1, active = true)))
        val reading = scaleReadingFixture(weightKg = 71.0, userIndex = null)
        val result = ingestor(dao, profiles).ingest(deviceAddress, reading)
        assertTrue(result is IngestResult.Held)
    }

    @Test
    fun aSecondReadingWithinTheDedupWindowIsSuppressedAsADuplicate() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        val instance = ingestor(dao, profiles)
        val firstReading = scaleReadingFixture(weightKg = 71.0, userIndex = 1, capturedAtMillis = 1_000L)
        val first = instance.ingest(deviceAddress, firstReading)
        assertTrue(first is IngestResult.Inserted)
        val secondReading = scaleReadingFixture(weightKg = 71.05, userIndex = 1, capturedAtMillis = 1_500L)
        val second = instance.ingest(deviceAddress, secondReading)
        assertTrue(second is IngestResult.Duplicate)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun aReadingOutsideTheDedupWindowIsNotSuppressed() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        val instance = ingestor(dao, profiles)
        instance.ingest(deviceAddress, scaleReadingFixture(weightKg = 71.0, userIndex = 1, capturedAtMillis = 0L))
        val second = instance.ingest(
            deviceAddress,
            scaleReadingFixture(weightKg = 71.0, userIndex = 1, capturedAtMillis = JUST_OUTSIDE_DEDUP_WINDOW_MILLIS),
        )
        assertTrue(second is IngestResult.Inserted)
        assertEquals(2, dao.rows.value.size)
    }

    private companion object {
        // Just past DedupPolicy.TIME_WINDOW_MILLIS so the second reading falls outside the window.
        const val JUST_OUTSIDE_DEDUP_WINDOW_MILLIS = 300_001L

        /** Raw 0xFFFE — one below the SIG "value unknown" sentinel the decoders filter. */
        const val NEAR_SENTINEL_RAW = 65_534.0

        /** That raw value scaled by the SIG 0.1 %/LSB resolution: 6553.4 %. */
        const val NEAR_SENTINEL_PCT = 6_553.4
    }
}
