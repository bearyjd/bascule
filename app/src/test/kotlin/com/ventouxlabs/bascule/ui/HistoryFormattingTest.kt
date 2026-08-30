package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.ui.fake.readingFixture
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * C8: both functions were private to `HistoryScreen.kt` and reachable only
 * through a composition. Every number they return is user-visible — the age on
 * every row, the backlog banner's duration, and the headline weight.
 */
class HistoryFormattingTest {

    // --- formatRelativeAge: three thresholds, tested on both sides of each.

    @Test
    fun underOneMinuteReadsAsJustNow() {
        assertEquals("just now", formatRelativeAge(0L))
        assertEquals("just now", formatRelativeAge(TimeUnit.SECONDS.toMillis(59)))
    }

    @Test
    fun exactlyOneMinuteSwitchesToMinutes() {
        assertEquals("1m", formatRelativeAge(TimeUnit.MINUTES.toMillis(1)))
        assertEquals("59m", formatRelativeAge(TimeUnit.MINUTES.toMillis(59)))
    }

    @Test
    fun exactlySixtyMinutesSwitchesToHours() {
        assertEquals("1h", formatRelativeAge(TimeUnit.MINUTES.toMillis(60)))
        assertEquals("23h", formatRelativeAge(TimeUnit.HOURS.toMillis(23)))
    }

    @Test
    fun exactlyTwentyFourHoursSwitchesToDays() {
        assertEquals("1d", formatRelativeAge(TimeUnit.HOURS.toMillis(24)))
        assertEquals("9d", formatRelativeAge(TimeUnit.DAYS.toMillis(9)))
    }

    /**
     * Truncation, not rounding: 90 minutes is "1h", never "2h". A reading that
     * has been waiting 1h59m must not claim it has been waiting two hours.
     */
    @Test
    fun partialUnitsTruncateRatherThanRound() {
        assertEquals("1h", formatRelativeAge(TimeUnit.MINUTES.toMillis(119)))
        assertEquals("1d", formatRelativeAge(TimeUnit.HOURS.toMillis(47)))
    }

    // --- formatWeight: storage is always kilograms; the user's current
    // --- preference decides the number shown, not the row's stored unit.

    @Test
    fun rendersKilogramsWhenTheUserSelectedKilograms() {
        assertEquals(
            "90.8",
            formatWeight(readingFixture(weightKg = 90.82, displayUnit = "kg"), WeightUnit.KILOGRAMS),
        )
    }

    @Test
    fun convertsToPoundsWhenTheUserSelectedPounds() {
        assertEquals(
            "200.2",
            formatWeight(readingFixture(weightKg = 90.82, displayUnit = "kg"), WeightUnit.POUNDS),
        )
    }

    /**
     * Replaces the old corrupt-`displayUnit` test, which pinned a real defect:
     * an unrecognised stored unit fell back to kilograms and rendered a wrong
     * number (90.8 where a pounds row should read 200.2) with nothing marking
     * it. Display no longer reads the stored string at all, so that failure
     * mode cannot occur — this test exists to keep the record that it once
     * could, and to fail if anyone reintroduces the dependency.
     */
    @Test
    fun aCorruptStoredUnitNoLongerAffectsWhatIsDisplayed() {
        val corrupt = readingFixture(weightKg = 90.82, displayUnit = "not-a-unit")
        assertEquals("200.2", formatWeight(corrupt, WeightUnit.POUNDS))
        assertEquals("90.8", formatWeight(corrupt, WeightUnit.KILOGRAMS))
    }

    @Test
    fun alwaysRendersExactlyOneDecimalPlace() {
        assertEquals("70.0", formatWeight(readingFixture(weightKg = 70.0), WeightUnit.KILOGRAMS))
        assertEquals("70.3", formatWeight(readingFixture(weightKg = 70.25), WeightUnit.KILOGRAMS))
    }
}
