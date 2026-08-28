package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.data.ReadingSource
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.delivery.DeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import com.ventouxlabs.bascule.ui.fake.FakeDeliveryTrigger
import com.ventouxlabs.bascule.ui.fake.FakeReadingDao
import com.ventouxlabs.bascule.ui.fake.MainDispatcherRule
import com.ventouxlabs.bascule.ui.fake.readingFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** WP-24: `ui/ManualEntryScreen.kt`'s ViewModel — inserts, never dedups, never touches BLE. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualEntryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        dao: FakeReadingDao = FakeReadingDao(),
        unit: WeightUnit = WeightUnit.KILOGRAMS,
        deliveryTrigger: DeliveryTrigger? = null,
        nowMillis: () -> Long = { FIXED_NOW_MILLIS },
    ) = ManualEntryViewModel(
        dao,
        FakeConfigStore(initialDisplayUnit = unit),
        nowMillis = nowMillis,
        deliveryTrigger = deliveryTrigger,
    )

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

    /**
     * C13: the `deliveryTrigger` collaborator was left null here too. A manual
     * entry is `PENDING` the instant it is saved, so it must not sit until the
     * next 15-minute periodic drain.
     */
    @Test
    fun aSavedEntryTriggersAnImmediateDrain() = runTest {
        val dao = FakeReadingDao()
        val trigger = FakeDeliveryTrigger()
        val vm = viewModel(dao, deliveryTrigger = trigger)
        advanceUntilIdle()

        vm.onWeightTextChanged("70")
        vm.save()
        advanceUntilIdle()

        assertEquals(1, trigger.triggerCount)
    }

    @Test
    fun aRejectedEntryNeverTriggersADrain() = runTest {
        val dao = FakeReadingDao()
        val trigger = FakeDeliveryTrigger()
        val vm = viewModel(dao, deliveryTrigger = trigger)
        advanceUntilIdle()

        vm.onWeightTextChanged("not a number")
        vm.save()
        advanceUntilIdle()

        assertEquals("nothing was inserted, so there is nothing to drain", 0, trigger.triggerCount)
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
     *
     * The values sit *on* the rounded bound, not near it: 20 kg is 44.0924…lb
     * raw and 44.09 rounded, 300 kg is 661.3868…lb raw and 661.39 rounded. So
     * accepting 44.09 proves HALF_UP rounded the floor *down* (the raw floor
     * would reject it) and accepting 661.39 proves it rounded the ceiling *up*
     * (the raw ceiling would reject that too). Values a whole pound clear of
     * the bound — as this test previously used — pass with the rounding
     * removed entirely.
     */
    @Test
    fun rejectsImplausibleWeightAtTheRoundedBoundariesInPounds() = runTest {
        val below = FakeReadingDao()
        val vm = viewModel(below, unit = WeightUnit.POUNDS)
        advanceUntilIdle()

        vm.onWeightTextChanged("44.08") // one hundredth under the 44.09 lb floor
        vm.save()
        advanceUntilIdle()
        assertTrue(below.rows.value.isEmpty())

        vm.onWeightTextChanged("661.40") // one hundredth over the 661.39 lb ceiling
        vm.save()
        advanceUntilIdle()
        assertTrue(below.rows.value.isEmpty())
    }

    @Test
    fun acceptsTheRoundedFloorInPounds() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao, unit = WeightUnit.POUNDS)
        advanceUntilIdle()

        vm.onWeightTextChanged("44.09") // exactly the HALF_UP-rounded 20kg floor
        vm.save()
        advanceUntilIdle()

        assertEquals(
            "the raw floor is 44.0924 lb — without setScale(2, HALF_UP) this value is rejected",
            1,
            dao.rows.value.size,
        )
    }

    @Test
    fun acceptsTheRoundedCeilingInPounds() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao, unit = WeightUnit.POUNDS)
        advanceUntilIdle()

        vm.onWeightTextChanged("661.39") // exactly the HALF_UP-rounded 300kg ceiling
        vm.save()
        advanceUntilIdle()

        assertEquals(
            "the raw ceiling is 661.3868 lb — without setScale(2, HALF_UP) this value is rejected",
            1,
            dao.rows.value.size,
        )
    }

    // --- C15: the post-save reset, and the state fields the screen actually reads.

    /**
     * A regression to a plain `ManualEntryUiState()` here would clear the text
     * and the saving flag exactly as intended, silently reset the user's
     * pounds selection back to kilograms, and pass every other test in this
     * file.
     */
    @Test
    fun aSuccessfulSaveClearsTheEntryButKeepsTheSelectedUnit() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao, unit = WeightUnit.POUNDS)
        advanceUntilIdle()

        vm.onWeightTextChanged("154")
        vm.save()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("", state.weightText)
        assertFalse(state.isSaving)
        assertNull(state.errorMessage)
        assertEquals(
            "the unit is the user's Config choice, not per-entry state — resetting it silently changes the next entry",
            WeightUnit.POUNDS,
            state.unit,
        )
    }

    /**
     * `isSaving` disables the weight field and swaps the button to "Saving…".
     * The re-entrancy test proves the guard works but never reads the flag the
     * screen binds to, so a `save()` that never set it would still pass there.
     */
    @Test
    fun isSavingReadsTrueWhileTheInsertIsInFlight() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("70")
        vm.save()

        assertTrue("set synchronously, before the insert coroutine is dispatched", vm.uiState.value.isSaving)

        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun aRejectedSaveNeverEntersTheSavingState() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("not a number")
        vm.save()

        assertFalse(
            "a validation failure must not disable the field the user has to correct",
            vm.uiState.value.isSaving,
        )
    }

    /**
     * Clearing on *edit*, not on the next save: the error sits under the field
     * the user is correcting, so it has to disappear as they type rather than
     * survive until they resubmit.
     */
    @Test
    fun editingTheWeightClearsAStandingErrorWithoutASave() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao)
        advanceUntilIdle()

        vm.onWeightTextChanged("not a number")
        vm.save()
        advanceUntilIdle()
        assertEquals("Enter a number", vm.uiState.value.errorMessage)

        vm.onWeightTextChanged("7")

        assertNull(vm.uiState.value.errorMessage)
        assertEquals("7", vm.uiState.value.weightText)
        assertTrue("clearing the message must not have saved anything", dao.rows.value.isEmpty())
    }

    /**
     * C15's root cause: `save()` read the wall clock directly, so neither
     * timestamp could be asserted. `retryEpochMillis` matching `capturedAtMillis`
     * is what makes a manual entry eligible for the very next drain instead of
     * parking behind a backoff it never earned.
     */
    @Test
    fun bothTimestampsComeFromTheInjectedClock() = runTest {
        val dao = FakeReadingDao()
        val vm = viewModel(dao, nowMillis = { FIXED_NOW_MILLIS })
        advanceUntilIdle()

        vm.onWeightTextChanged("70")
        vm.save()
        advanceUntilIdle()

        val row = dao.rows.value.single()
        assertEquals(FIXED_NOW_MILLIS, row.capturedAtMillis)
        assertEquals(FIXED_NOW_MILLIS, row.retryEpochMillis)
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

    private companion object {
        const val FIXED_NOW_MILLIS = 1_787_000_000_000L
    }
}
