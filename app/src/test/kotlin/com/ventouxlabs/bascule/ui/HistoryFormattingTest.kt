package com.ventouxlabs.bascule.ui

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

    // --- formatWeight: storage is always kilograms, so displayUnit decides the number shown.

    @Test
    fun rendersTheStoredKilogramsWhenTheDisplayUnitIsKilograms() {
        assertEquals("90.8", formatWeight(readingFixture(weightKg = 90.82, displayUnit = "kg")))
    }

    @Test
    fun convertsToPoundsWhenTheDisplayUnitIsPounds() {
        assertEquals("200.2", formatWeight(readingFixture(weightKg = 90.82, displayUnit = "lbs")))
    }

    /**
     * The corrupt-unit case, pinned as-is rather than fixed: an unrecognized
     * `displayUnit` falls back to kilograms, so the row renders a **wrong
     * number** — 90.8 where the user's pounds row should read 200.2 — with no
     * error anywhere. Changing this is a product decision; this test exists so
     * the behaviour cannot change silently.
     */
    @Test
    fun aCorruptDisplayUnitSilentlyFallsBackToKilograms() {
        val corrupt = readingFixture(weightKg = 90.82, displayUnit = "stones")
        val pounds = readingFixture(weightKg = 90.82, displayUnit = "lbs")

        assertEquals("90.8", formatWeight(corrupt))
        assertEquals(
            "the fallback is indistinguishable from a genuine kg row — no marker reaches the user",
            formatWeight(readingFixture(weightKg = 90.82, displayUnit = "kg")),
            formatWeight(corrupt),
        )
        assertEquals("200.2", formatWeight(pounds))
    }

    @Test
    fun alwaysRendersExactlyOneDecimalPlace() {
        assertEquals("70.0", formatWeight(readingFixture(weightKg = 70.0)))
        assertEquals("70.3", formatWeight(readingFixture(weightKg = 70.25)))
    }
}
