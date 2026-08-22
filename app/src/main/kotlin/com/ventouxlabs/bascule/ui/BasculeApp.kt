package com.ventouxlabs.bascule.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * UI is out of scope for Phase 2. These placeholders exist so the module graph
 * of 00-design.md §1.1 is complete and the app assembles.
 */
@Composable
fun BasculeApp() {
    MaterialTheme {
        Surface {
            Column(Modifier.padding(16.dp)) {
                Text("Bascule", style = MaterialTheme.typography.headlineMedium)
                HistoryScreen()
                ManualEntryScreen()
                ConfigScreen()
            }
        }
    }
}
