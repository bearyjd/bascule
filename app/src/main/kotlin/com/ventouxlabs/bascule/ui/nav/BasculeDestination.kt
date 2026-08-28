package com.ventouxlabs.bascule.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The app's four top-level, peer screens — no destination is nested under
 * another, so a flat bottom-navigation bar is the lowest-friction shell:
 * every screen is one tap away from every other, always.
 */
enum class BasculeDestination(val route: String, val label: String, val icon: ImageVector) {
    History(route = "history", label = "History", icon = Icons.AutoMirrored.Filled.List),
    ManualEntry(route = "manual_entry", label = "Add weight", icon = Icons.Filled.Add),
    Scale(route = "scale", label = "Scale", icon = Icons.Filled.MonitorWeight),
    Config(route = "config", label = "Settings", icon = Icons.Filled.Settings),
}
