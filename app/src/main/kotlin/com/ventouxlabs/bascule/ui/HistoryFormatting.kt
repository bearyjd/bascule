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
 * Falls back to kilograms when the persisted `displayUnit` matches no
 * [WeightUnit], so a corrupt pounds row renders a *wrong number* rather than
 * an error. Pinned by test rather than fixed here — changing it is a product
 * decision about what to show when storage disagrees with itself.
 */
internal fun formatWeight(reading: ReadingEntity): String {
    val unit = WeightUnit.entries.firstOrNull { it.wire == reading.displayUnit } ?: WeightUnit.KILOGRAMS
    return "%.1f".format(unit.fromKilograms(reading.weightKg))
}

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
