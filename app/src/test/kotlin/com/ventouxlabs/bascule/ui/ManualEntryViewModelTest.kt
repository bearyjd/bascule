package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.data.ReadingSource
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import com.ventouxlabs.bascule.ui.fake.readingFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    fun rejectsImplausibleWeightAtBoundaries() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("19.9") // just under the 20kg floor
        vm.save()
        advanceUntilIdle()
        assertTrue(dao.rows.value.isEmpty())

        vm.onWeightTextChanged("300.1") // just over the 300kg ceiling
        vm.save()
        advanceUntilIdle()
        assertTrue(dao.rows.value.isEmpty())

        vm.onWeightTextChanged("300") // exactly at the ceiling: still plausible
        vm.save()
        advanceUntilIdle()
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun doesNotDedupAgainstScaleRows() = runTest {
        val dao = FakeReadingDao()
        dao.insert(readingFixture(source = ReadingSource.SCALE, weightKg = 70.0))
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("70") // identical weight to the existing scale row
        vm.save()
        advanceUntilIdle()

        assertEquals(
            "a manual entry's source alone is what keeps it out of the scale dedup corpus (§3.3)",
            2,
            dao.rows.value.size,
        )
    }
}
