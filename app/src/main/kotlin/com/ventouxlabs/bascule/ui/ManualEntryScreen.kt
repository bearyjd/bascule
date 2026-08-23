package com.ventouxlabs.bascule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ventouxlabs.bascule.BasculeApplication

/**
 * PWA-parity quick entry (WP-24). One field, one button — the fewest taps a
 * weight can possibly take to reach [com.ventouxlabs.bascule.data.ReadingStatus.PENDING]
 * without a scale at all. Inserts `source = MANUAL`, which never dedups
 * against a scale reading (`00-design.md` §3.3).
 */
@Composable
fun ManualEntryScreen(
    onSaved: () -> Unit = {},
    viewModel: ManualEntryViewModel = viewModel(
        factory = ManualEntryViewModel.factory(LocalContext.current.applicationContext as BasculeApplication),
    ),
) {
    val state by viewModel.uiState.collectAsState()

    // A one-shot event, not a state field derived from uiState — the
    // ViewModel is retained across bottom-nav tab switches (saveState /
    // restoreState), so keying LaunchedEffect off a sticky "saved" boolean
    // would re-fire onSaved() on every return to this tab, not just the save
    // that actually set it.
    LaunchedEffect(viewModel) {
        viewModel.savedEvents.collect { onSaved() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "How much did you weigh?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Only for when the scale doesn't have you — this is logged instantly, no scale needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        OutlinedTextField(
            value = state.weightText,
            onValueChange = viewModel::onWeightTextChanged,
            label = { Text("Weight (${state.unit.wire})") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = state.errorMessage != null,
            supportingText = state.errorMessage?.let { { Text(it) } },
            singleLine = true,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = viewModel::save,
            enabled = !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        ) {
            Text(if (state.isSaving) "Saving…" else "Save")
        }
    }
}
