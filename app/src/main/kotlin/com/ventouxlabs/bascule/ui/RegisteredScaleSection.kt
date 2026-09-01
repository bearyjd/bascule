package com.ventouxlabs.bascule.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The scale registration card. Lives on the Scale tab
 * ([ScaleScreen]) rather than in [ConfigScreen], which is where it was first
 * written and its only consumer has never been.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // UI states mirror the registration state machine directly.
internal fun RegisteredScaleSection(
    pairedDeviceAddress: String?,
    registeredUserIndex: Int?,
    registration: ScaleRegistrationUiState,
    onRegister: (Boolean) -> Unit,
    onLinkExisting: (String, String, String) -> Unit,
    onReRegister: () -> Unit,
) {
    var showWarning by remember { mutableStateOf(false) }
    var showExisting by remember { mutableStateOf(false) }
    val busy = registration == ScaleRegistrationUiState.Scanning ||
        registration == ScaleRegistrationUiState.Connecting

    SectionCard(title = "Scale registration") {
        Text(
            registeredUserIndex?.let { "Registered as user slot $it" } ?: "Not registered with a scale yet",
            style = MaterialTheme.typography.bodyMedium,
        )
        when (registration) {
            ScaleRegistrationUiState.Idle -> Unit
            ScaleRegistrationUiState.Scanning -> Text("Scanning… wake or step on the scale.")
            ScaleRegistrationUiState.Connecting -> Text("Scale found — registering…")
            is ScaleRegistrationUiState.Success -> Text(
                "Connected to ${registration.address}; user slot ${registration.scaleIndex} is saved.",
                color = MaterialTheme.colorScheme.primary,
            )
            is ScaleRegistrationUiState.Failure -> Text(
                registration.message,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            // registeredUserIndex != null && pairedDeviceAddress == null
            // renders neither branch — "Use existing" ends up alone, so its
            // leading gap only belongs there when something precedes it
            // (devil's-advocate review, L-6).
            val hasLeadingButton = registeredUserIndex == null || pairedDeviceAddress != null
            if (registeredUserIndex == null) {
                Button(onClick = { showWarning = true }, enabled = !busy) {
                    Text("Register scale")
                }
            } else if (pairedDeviceAddress != null) {
                OutlinedButton(onClick = { showWarning = true }, enabled = !busy) {
                    Text("Re-register with the scale")
                }
            }
            // Always reachable, not just before the first registration: a
            // second (or third) known slot on the same BF720 is linked the
            // same way the first one was — see ConfigViewModel.linkExistingScale.
            OutlinedButton(
                onClick = { showExisting = true },
                enabled = !busy,
                modifier = if (hasLeadingButton) Modifier.padding(start = 8.dp) else Modifier,
            ) {
                Text("Use existing")
            }
        }
    }

    if (showWarning) {
        val replacing = registeredUserIndex != null && pairedDeviceAddress != null
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text(if (replacing) "Re-register with the scale?" else "Register a scale?") },
            text = {
                Text(
                    if (replacing) {
                        "The BF720 only has 8 user slots. Re-registering uses one of them, and the old " +
                            "slot is left behind on the scale until it's overwritten or reset."
                    } else {
                        "Wake or step on the BF720 after continuing. Registration uses one of the scale's " +
                            "8 user slots and securely saves the assigned slot on this device."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (replacing) onReRegister() else onRegister(false)
                    showWarning = false
                }) { Text(if (replacing) "Re-register" else "Start scanning") }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) { Text("Cancel") }
            },
        )
    }

    if (showExisting) {
        ExistingScaleDialog(
            onDismiss = { showExisting = false },
            onConfirm = { address, index, code ->
                onLinkExisting(address, index, code)
                showExisting = false
            },
        )
    }
}

@Composable
private fun ExistingScaleDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var index by remember { mutableStateOf("") }
    var consentCode by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use existing registration") },
        text = {
            Column {
                Text("Enter a previously exported or recorded scale mapping. This does not use a new scale slot.")
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Bluetooth address") },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = index,
                    onValueChange = { index = it },
                    label = { Text("User slot") },
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = consentCode,
                    onValueChange = { consentCode = it },
                    label = { Text("Consent code") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(address, index, consentCode) },
                enabled = address.isNotBlank() && index.isNotBlank() && consentCode.isNotBlank(),
            ) { Text("Save mapping") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
