package com.ventouxlabs.bascule.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.network.ContractVersion

/**
 * WP-25: §5's config surface plus §6.3's permission-request flow. The token
 * field never renders a saved value back — only "set" / "not set" — and the
 * registered user index is read-only, sourced from the consent store rather
 * than typed in here (O-08.5).
 */
@Composable
fun ConfigScreen(
    viewModel: ConfigViewModel = viewModel(
        factory = ConfigViewModel.factory(LocalContext.current.applicationContext as BasculeApplication),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val requester = remember {
        PermissionRequester(isGranted = { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        })
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PermissionSection(requester) }
        item { ConnectionSection(state, onSaveBaseUrl = viewModel::saveBaseUrl) }
        item {
            UnitAndContractSection(
                state = state,
                onUnitChanged = viewModel::saveDisplayUnit,
                onContractChanged = viewModel::saveContractVersion,
            )
        }
        item { TokenSection(state, onSaveToken = viewModel::saveToken, onClearToken = viewModel::clearToken) }
        item {
            RegisteredScaleSection(
                pairedDeviceAddress = state.pairedDeviceAddress,
                registeredUserIndex = state.registeredUserIndex,
                onReRegister = viewModel::reRegister,
            )
        }
        item {
            AlwaysOnSection(
                enabled = state.alwaysOnBridging,
                onToggle = viewModel::saveAlwaysOnBridging,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Column(modifier = Modifier.padding(top = 12.dp)) { content() }
        }
    }
}

@Composable
private fun PermissionSection(requester: PermissionRequester) {
    var pending by remember { mutableStateOf(requester.firstDialogPermissions()) }
    var awaitingBackgroundLocation by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending = requester.firstDialogPermissions()
        awaitingBackgroundLocation = requester.secondDialogPermission() != null
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { awaitingBackgroundLocation = false }

    if (pending.isEmpty() && !awaitingBackgroundLocation) return

    SectionCard(title = "Permissions needed") {
        if (requester.needsLocationRationale() && pending.isNotEmpty()) {
            Text(
                "Bascule asks for location because Android requires it to detect nearby Bluetooth " +
                    "devices on this version of Android — it is never used to track where you are.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        if (pending.isNotEmpty()) {
            Button(onClick = { launcher.launch(pending.toTypedArray()) }) {
                Text("Grant permissions")
            }
        } else if (awaitingBackgroundLocation) {
            Text(
                "One more: allow background location so weigh-ins are detected even when Bascule " +
                    "isn't open.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Button(onClick = {
                requester.secondDialogPermission()?.let { backgroundLocationLauncher.launch(it) }
            }) {
                Text("Grant background location")
            }
        }
    }
}

@Composable
private fun ConnectionSection(state: ConfigUiState, onSaveBaseUrl: (String) -> Unit) {
    var urlText by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }

    SectionCard(title = "VitalForge server") {
        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            label = { Text("Base URL") },
            isError = state.baseUrlError != null,
            supportingText = state.baseUrlError?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSaveBaseUrl(urlText) },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Save")
        }
    }
}

@Composable
private fun UnitAndContractSection(
    state: ConfigUiState,
    onUnitChanged: (WeightUnit) -> Unit,
    onContractChanged: (ContractVersion) -> Unit,
) {
    SectionCard(title = "Units and contract") {
        LabeledDropdown(
            label = "Weight unit",
            options = WeightUnit.entries,
            optionLabel = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
            selected = state.displayUnit,
            onSelected = onUnitChanged,
        )
        LabeledDropdown(
            label = "VitalForge contract version",
            options = ContractVersion.entries,
            optionLabel = { "v${it.wire}" },
            selected = state.contractVersion,
            onSelected = onContractChanged,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> LabeledDropdown(
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TokenSection(state: ConfigUiState, onSaveToken: (String) -> Unit, onClearToken: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var tokenText by remember { mutableStateOf("") }

    SectionCard(title = "VitalForge token") {
        Text(
            if (state.tokenIsSet) "Token is set" else "Token is not set",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (editing) {
            OutlinedTextField(
                value = tokenText,
                onValueChange = { tokenText = it },
                label = { Text("New token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    onSaveToken(tokenText)
                    tokenText = ""
                    editing = false
                }) { Text("Save token") }
                TextButton(onClick = { editing = false; tokenText = "" }, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Cancel")
                }
            }
        } else {
            Row(modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { editing = true }) {
                    Text(if (state.tokenIsSet) "Replace token" else "Set token")
                }
                if (state.tokenIsSet) {
                    TextButton(onClick = onClearToken, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Clear")
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisteredScaleSection(
    pairedDeviceAddress: String?,
    registeredUserIndex: Int?,
    onReRegister: (String) -> Unit,
) {
    var showWarning by remember { mutableStateOf(false) }

    SectionCard(title = "Scale registration") {
        Text(
            registeredUserIndex?.let { "Registered as user slot $it" } ?: "Not registered with a scale yet",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (registeredUserIndex != null && pairedDeviceAddress != null) {
            OutlinedButton(
                onClick = { showWarning = true },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Re-register with the scale")
            }
        }
    }

    if (showWarning && pairedDeviceAddress != null) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text("Re-register with the scale?") },
            text = {
                Text(
                    "The BF720 only has 8 user slots. Re-registering uses one of them, and the old " +
                        "slot is left behind on the scale until it's overwritten or reset.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onReRegister(pairedDeviceAddress)
                    showWarning = false
                }) { Text("Re-register") }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AlwaysOnSection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    SectionCard(title = "Always-on bridging") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.padding(end = 16.dp)) {
                Text(
                    "Keep scanning in the foreground",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Off by default — the background wake path is normally enough on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}
