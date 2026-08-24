@file:Suppress("MaxLineLength")

package com.ventouxlabs.bascule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ventouxlabs.bascule.BasculeApplication
import androidx.compose.ui.platform.LocalContext
import java.text.DateFormat
import java.util.Date

@Composable
fun ScaleScreen() {
    val app = LocalContext.current.applicationContext as BasculeApplication
    val vm: ScaleViewModel = viewModel(factory = ScaleViewModel.factory(app))
    val configVm: ConfigViewModel = viewModel(factory = ConfigViewModel.factory(app))
    val state by vm.uiState.collectAsState()
    val configState by configVm.uiState.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Scale")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Capture")
                ToggleRow("Automatic background capture", state.automaticCaptureEnabled, vm::setAutomaticCapture)
                ToggleRow("Always-on foreground fallback", state.alwaysOnBridging, vm::setAlwaysOnBridging)
                Text("Pending deliveries: ${state.pendingDeliveries}")
                Text("Last successful capture: ${state.lastCaptureMillis?.let(::formatTime) ?: "Never"}")
                state.diagnostic?.let { Text(it) }
            }
        }
        RegisteredScaleSection(
            pairedDeviceAddress = configState.pairedDeviceAddress,
            registeredUserIndex = configState.registeredUserIndex,
            registration = configState.scaleRegistration,
            onRegister = configVm::startScaleRegistration,
            onLinkExisting = configVm::linkExistingScale,
            onReRegister = configVm::reRegister,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Profiles")
                if (state.profiles.isEmpty()) Text("No locally known profiles. Register or link one from Settings.")
                state.profiles.forEach { profile ->
                    var editing by remember(profile.id, profile.label) { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(profile.active, onClick = { vm.setActive(profile.id) })
                        Column(Modifier.weight(1f)) {
                            Text(profile.label)
                            Text("${profile.deviceAddress} · slot ${profile.scaleIndex}")
                            Text("Last verified: ${profile.lastVerifiedAtMillis?.let(::formatTime) ?: "Not yet"}")
                        }
                        TextButton(onClick = { editing = !editing }) { Text(if (editing) "Cancel" else "Rename") }
                    }
                    if (editing) InlineLabelEditor(profile.label) { vm.rename(profile, it); editing = false }
                }
                Text("Scale inventory may be incomplete until List All Users capability probing is supported.")
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scale status")
                Text("Battery and live database-change status are available only during a capability probe.")
                Text("Profile deletion is disabled until consent verification and typed confirmation can be completed safely.")
            }
        }
    }
}

@Composable private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onCheckedChange = onChange)
    }
}

@Composable private fun InlineLabelEditor(initial: String, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(value, { value = it }, Modifier.weight(1f), singleLine = true)
        TextButton(onClick = { onSave(value) }) { Text("Save") }
    }
}

private fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance().format(Date(millis))
