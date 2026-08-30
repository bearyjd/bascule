package com.ventouxlabs.bascule.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The app's four top-level routes. Three are peers in the bottom bar, so each
 * is one tap from the others. [ManualEntry] is deliberately excluded: it is
 * the fallback for when the scale misses you, and giving the rarest action a
 * quarter of primary navigation — while *also* exposing it through History's
 * FAB — gave one destination two back-stack contracts (P25).
 */
enum class BasculeDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val inBottomBar: Boolean = true,
) {
    History(route = "history", label = "History", icon = Icons.AutoMirrored.Filled.List),
    ManualEntry(route = "manual_entry", label = "Add weight", icon = Icons.Filled.Add, inBottomBar = false),
    Scale(route = "scale", label = "Scale", icon = Icons.Filled.MonitorWeight),
    Config(route = "config", label = "Settings", icon = Icons.Filled.Settings),
    ;

    companion object {
        val bottomBarEntries: List<BasculeDestination> = entries.filter { it.inBottomBar }
    }
}
