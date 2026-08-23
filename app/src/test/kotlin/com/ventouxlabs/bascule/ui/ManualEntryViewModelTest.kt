package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.data.ReadingSource
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import com.ventouxlabs.bascule.ui.fake.readingFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** WP-24: `ui/ManualEntryScreen.kt`'s ViewModel — inserts, never dedups, never touches BLE. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualEntryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(dao: FakeReadingDao = FakeReadingDao(), unit: WeightUnit = WeightUnit.KILOGRAMS) =
        ManualEntryViewModel(dao, FakeConfigStore(initialDisplayUnit = unit))

    @Test
    fun insertsWithSourceManual() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("70")
        vm.save()
        advanceUntilIdle()

        assertEquals(ReadingSource.MANUAL, dao.rows.value.single().source)
    }

    @Test
    fun saveIgnoresASecondCallWhileTheFirstInsertIsStillInFlight() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("70")
        vm.save()
        vm.save() // isSaving is set synchronously before the first insert's coroutine ever runs
        advanceUntilIdle()

        assertEquals("two taps before the first insert completes must not double-insert", 1, dao.rows.value.size)
    }

    @Test
    fun savedEventsFiresExactlyOncePerSave() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()
        var eventCount = 0
        val collector = launch { vm.savedEvents.collect { eventCount++ } }

        vm.onWeightTextChanged("70")
        vm.save()
        advanceUntilIdle()

        assertEquals(1, eventCount)
        collector.cancel()
    }

    @Test
    fun insertsAsPendingNotHeldConfirm() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("70")
        vm.save()
        advanceUntilIdle()

        assertEquals(
            "a manual entry is attributed by construction and skips the confirm gate entirely",
            ReadingStatus.PENDING,
            dao.rows.value.single().status,
        )
    }

    @Test
    fun bodyCompFieldsAreNull() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("70")
        vm.save()
        advanceUntilIdle()

        val row = dao.rows.value.single()
        assertNull(row.bodyFatPct)
        assertNull(row.bodyWaterPct)
        assertNull(row.musclePct)
        assertNull(row.boneMassKg)
        assertNull(row.bmi)
        assertNull(row.bmr)
        assertNull(row.amr)
        assertNull(row.impedanceOhms)
        assertNull(row.softLeanMassKg)
        assertNull(row.userIndex)
        assertNull(row.scaleTimestampMillis)
    }

    @Test
    fun convertsDisplayUnitToCanonicalKg() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao, unit = WeightUnit.POUNDS)
        advanceUntilIdle()

        vm.onWeightTextChanged("154")
        vm.save()
        advanceUntilIdle()

        val row = dao.rows.value.single()
        assertEquals(69.85, row.weightKg, 0.01)
        assertEquals("lbs", row.displayUnit)
    }

    /**
     * Regression test: the unit used to be read once via `.first()` at
     * construction. If Config changes it while this screen is retained
     * (a tab switch, not a fresh navigation, thanks to bottom-nav's
     * `saveState`/`restoreState`), the label and the kg conversion would
     * keep using a unit the user no longer has selected.
     */
    @Test
    fun updatesTheDisplayedUnitWhenConfigChangesItWhileThisScreenIsRetained() = runTest {
        val configStore = FakeConfigStore(initialDisplayUnit = WeightUnit.KILOGRAMS)
        val dao = FakeReadingDao()
        val vm = ManualEntryViewModel(dao, configStore)
        advanceUntilIdle()
        assertEquals(WeightUnit.KILOGRAMS, vm.uiState.value.unit)

        configStore.saveDisplayUnit(WeightUnit.POUNDS)
        advanceUntilIdle()

        assertEquals(WeightUnit.POUNDS, vm.uiState.value.unit)
    }

    @Test
    fun rejectsNonNumericInput() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("not a number")
        vm.save()
        advanceUntilIdle()

        assertTrue(dao.rows.value.isEmpty())
        assertEquals("Enter a number", vm.uiState.value.errorMessage)
    }

    @Test
    fun rejectsImplausibleWeightAtBoundariesInKilograms() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("19.9") // just under the 20kg floor
        vm.save()
        advanceUntilIdle()
        assertTrue(dao.rows.value.isEmpty())
        assertEquals(
            "a silently-swallowed failure would also leave dao.rows empty — assert the reason, not just the absence",
            "That doesn't look like a plausible weight",
            vm.uiState.value.errorMessage,
        )

        vm.onWeightTextChanged("300.1") // just over the 300kg ceiling
        vm.save()
        advanceUntilIdle()
        assertTrue(dao.rows.value.isEmpty())

        vm.onWeightTextChanged("300") // exactly at the ceiling: still plausible
        vm.save()
        advanceUntilIdle()
        assertEquals(1, dao.rows.value.size)
    }

    /**
     * The kg boundary test above never exercises `fromKilograms`'s per-unit
     * derivation (`BigDecimal.setScale(2, HALF_UP)`) — this does, in the unit
     * where the bound is actually computed, not just compared.
     */
    @Test
    fun rejectsImplausibleWeightAtBoundariesInPounds() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao, unit = WeightUnit.POUNDS)
        advanceUntilIdle()

        vm.onWeightTextChanged("44") // well under the ~44.09 lb floor (20kg)
        vm.save()
        advanceUntilIdle()
        assertTrue(dao.rows.value.isEmpty())

        vm.onWeightTextChanged("662") // well over the ~661.39 lb ceiling (300kg)
        vm.save()
        advanceUntilIdle()
        assertTrue(dao.rows.value.isEmpty())

        vm.onWeightTextChanged("154") // comfortably inside both bounds
        vm.save()
        advanceUntilIdle()
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun rejectsNonFiniteInput() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()

        // toDoubleOrNull() accepts both — neither compares true or false
        // against a plausibility bound, so isFinite() must gate them out
        // explicitly or they'd sail past both min and max checks.
        vm.onWeightTextChanged("NaN")
        vm.save()
        advanceUntilIdle()
        assertTrue("a NaN weight must never persist — it can't dedup or serialize", dao.rows.value.isEmpty())

        vm.onWeightTextChanged("Infinity")
        vm.save()
        advanceUntilIdle()
        assertTrue(dao.rows.value.isEmpty())
    }

    /**
     * §3.3's `source` clause — excluding manual rows from the scale dedup
     * corpus — is enforced by `ReadingDao.dedupCandidates`'s own query filter
     * (`source = :source`), not by this ViewModel. What belongs to this
     * ViewModel's own contract, and what this actually proves: it performs no
     * pre-insert dedup check of its own, so an identical-weight scale row
     * already on record never silently suppresses a manual one.
     */
    @Test
    fun neverSuppressesAnInsertEvenWhenAnIdenticalScaleRowAlreadyExists() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(source = ReadingSource.SCALE, weightKg = 70.0))
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("70") // identical weight to the existing scale row
        vm.save()
        advanceUntilIdle()

        assertEquals(2, dao.rows.value.size)
    }
}
