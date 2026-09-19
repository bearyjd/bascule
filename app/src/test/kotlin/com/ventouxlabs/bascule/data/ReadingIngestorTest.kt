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
        val firstReading = scaleReadingFixture(weightKg = 71.0, userIndex = 1, receivedAtMillis = 1_000L)
        val first = instance.ingest(deviceAddress, firstReading)
        assertTrue(first is IngestResult.Inserted)
        val secondReading = scaleReadingFixture(weightKg = 71.05, userIndex = 1, receivedAtMillis = 1_500L)
        val second = instance.ingest(deviceAddress, secondReading)
        assertTrue(second is IngestResult.Duplicate)
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun aReadingOutsideTheDedupWindowIsNotSuppressed() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        val instance = ingestor(dao, profiles)
        instance.ingest(deviceAddress, scaleReadingFixture(weightKg = 71.0, userIndex = 1, receivedAtMillis = 0L))
        val second = instance.ingest(
            deviceAddress,
            scaleReadingFixture(weightKg = 71.0, userIndex = 1, receivedAtMillis = JUST_OUTSIDE_DEDUP_WINDOW_MILLIS),
        )
        assertTrue(second is IngestResult.Inserted)
        assertEquals(2, dao.rows.value.size)
    }

    /**
     * §3.3's window keys on the *resolved* capture time — the scale's clock —
     * not on when the phone received the reading. Since #28 the scale can hand
     * the same stored weigh-in over again in a later session, hours after the
     * first delivery; keyed on receipt, the two deliveries would land far
     * outside the 5-minute window and the weigh-in would be persisted twice.
     */
    @Test
    fun theSameStoredWeighInDeliveredAgainHoursLaterIsADuplicate() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        val instance = ingestor(dao, profiles)
        val weighIn = RECEIVED_AT - 60_000L

        val first = instance.ingest(deviceAddress, storedWeighIn(scaleTime = weighIn, receivedAt = RECEIVED_AT))
        val second = instance.ingest(
            deviceAddress,
            storedWeighIn(scaleTime = weighIn, receivedAt = RECEIVED_AT + THREE_HOURS_MILLIS),
        )

        assertTrue(first is IngestResult.Inserted)
        assertTrue(
            "same weigh-in, same scale time — receipt three hours apart changes nothing",
            second is IngestResult.Duplicate,
        )
        assertEquals(1, dao.rows.value.size)
    }

    /** The mirror image: two real weigh-ins the scale stored and then handed over back-to-back in one session. */
    @Test
    fun twoStoredWeighInsTenMinutesApartDeliveredSecondsApartAreBothKept() = runTest {
        val dao = FakeReadingDao()
        val profiles = FakeScaleProfileStore(listOf(profile("p1", 1, active = true)))
        val instance = ingestor(dao, profiles)
        val firstWeighIn = RECEIVED_AT - 20 * 60_000L
        val secondWeighIn = firstWeighIn + 10 * 60_000L

        val first = instance.ingest(deviceAddress, storedWeighIn(scaleTime = firstWeighIn, receivedAt = RECEIVED_AT))
        val second = instance.ingest(
            deviceAddress,
            storedWeighIn(scaleTime = secondWeighIn, receivedAt = RECEIVED_AT + 5_000L),
        )

        assertTrue(first is IngestResult.Inserted)
        assertTrue(
            "ten minutes apart on the scale is two weigh-ins, however close the deliveries",
            second is IngestResult.Inserted,
        )
        assertEquals(2, dao.rows.value.size)
    }

    /** Same weight and user every time, so the resolved capture time is the only thing the dedup gate can act on. */
    private fun storedWeighIn(scaleTime: Long, receivedAt: Long) = scaleReadingFixture(
        weightKg = 71.0,
        userIndex = 1,
        receivedAtMillis = receivedAt,
        scaleTimestampMillis = scaleTime,
    )

    private companion object {
        /** A realistic epoch: the fixture's default of zero puts any real scale time outside the believable window. */
        const val RECEIVED_AT = 1_787_000_000_000L

        const val THREE_HOURS_MILLIS = 3 * 60 * 60 * 1000L

        // Just past DedupPolicy.TIME_WINDOW_MILLIS so the second reading falls outside the window.
        const val JUST_OUTSIDE_DEDUP_WINDOW_MILLIS = 300_001L

        /** Raw 0xFFFE — one below the SIG "value unknown" sentinel the decoders filter. */
        const val NEAR_SENTINEL_RAW = 65_534.0

        /** That raw value scaled by the SIG 0.1 %/LSB resolution: 6553.4 %. */
        const val NEAR_SENTINEL_PCT = 6_553.4
    }
}
