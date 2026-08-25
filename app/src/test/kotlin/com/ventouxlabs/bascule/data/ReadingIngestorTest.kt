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
    }
}
