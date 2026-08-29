package com.ventouxlabs.bascule.ui

import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.WeightUnit
import java.util.concurrent.TimeUnit

/**
 * `HistoryScreen`'s presentation logic, kept out of the Compose file so the
 * JVM test lane can call it: both functions are pure, both feed user-visible
 * numbers, and neither is a composable.
 */

/**
 * Storage is always kilograms (`00-design.md` §2.7); `ReadingEntity.displayUnit`
 * records only which unit was on screen when the row was captured, which is a
 * UI preference rather than part of the measurement. Rendering therefore uses
 * the user's *current* unit — the stored string is not consulted, so a corrupt
 * one can no longer produce a silently wrong number.
 */
internal fun formatWeight(reading: ReadingEntity, unit: WeightUnit): String =
    "%.1f".format(unit.fromKilograms(reading.weightKg))

/** Coarse, dependency-free relative-age formatting — no locale-aware library is in this project's dependency set. */
internal fun formatRelativeAge(millis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val days = TimeUnit.MILLISECONDS.toDays(millis)
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "${minutes}m"
        hours < HOURS_PER_DAY -> "${hours}h"
        else -> "${days}d"
    }
}

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
