package com.ventouxlabs.bascule.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.data.SettingsBackupCodec
import com.ventouxlabs.bascule.network.ContractVersion
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WP-25: §5's config surface plus §6.3's permission-request flow. The token
 * field never renders a saved value back — only "set" / "not set" — and the
 * registered user index is read-only, sourced from the consent store rather
 * than typed in here (O-08.5).
 */
@Composable
@Suppress("LongMethod") // Declarative list of independent settings cards.
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
        item {
            ConnectionSection(
                state = state,
                onBaseUrlTextChanged = viewModel::onBaseUrlTextChanged,
                onSaveBaseUrl = viewModel::saveBaseUrl,
                onTestConnection = viewModel::testConnection,
            )
        }
        item {
            UnitAndContractSection(
                state = state,
                onUnitChanged = viewModel::saveDisplayUnit,
                onContractChanged = viewModel::saveContractVersion,
            )
        }
        item {
            CredentialsSection(
                state = state,
                loginSucceeded = viewModel.loginSucceeded,
                onSaveToken = viewModel::saveToken,
                onLogin = viewModel::login,
                onClearCredentials = viewModel::clearCredentials,
            )
        }
        item {
            RegisteredScaleSection(
                pairedDeviceAddress = state.pairedDeviceAddress,
                registeredUserIndex = state.registeredUserIndex,
                registration = state.scaleRegistration,
                onRegister = viewModel::startScaleRegistration,
                onLinkExisting = viewModel::linkExistingScale,
                onReRegister = viewModel::reRegister,
            )
        }
        item {
            SettingsTransferSection(
                onExport = viewModel::exportSettings,
                onImport = viewModel::importSettings,
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
@Suppress("LongMethod") // Permission launchers and their SDK-specific UI are intentionally colocated.
private fun PermissionSection(requester: PermissionRequester) {
    var pending by remember { mutableStateOf(requester.firstDialogPermissions()) }
    // Must reflect requester's actual state on first composition, not just
    // after the first-dialog callback fires — a returning user on API 29/30
    // who already granted fine location in a prior session composes straight
    // into this state, and initializing to false would hide the second
    // dialog forever (there's no other path that ever sets it true).
    var awaitingBackgroundLocation by remember { mutableStateOf(requester.secondDialogPermission() != null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, requester) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                pending = requester.firstDialogPermissions()
                awaitingBackgroundLocation = requester.secondDialogPermission() != null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending = requester.firstDialogPermissions()
        awaitingBackgroundLocation = requester.secondDialogPermission() != null
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Re-derive from the requester rather than hardcoding false: a denial
        // must not permanently hide this prompt, since the user can still
        // grant it later from system settings and the app should offer it
        // again on the next composition.
        awaitingBackgroundLocation = requester.secondDialogPermission() != null
    }

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
            if (requester.backgroundLocationRequiresSettings()) {
                // On API 30 the runtime dialog can no longer grant "Allow all
                // the time" at all — only the app's own Settings screen can.
                val context = LocalContext.current
                Button(onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                }) {
                    Text("Open settings")
                }
            } else {
                Button(onClick = {
                    requester.secondDialogPermission()?.let { backgroundLocationLauncher.launch(it) }
                }) {
                    Text("Grant background location")
                }
            }
        }
    }
}

@Composable
private fun ConnectionSection(
    state: ConfigUiState,
    onBaseUrlTextChanged: () -> Unit,
    onSaveBaseUrl: (String) -> Unit,
    onTestConnection: () -> Unit,
) {
    var urlText by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }

    SectionCard(title = "VitalForge server") {
        OutlinedTextField(
            value = urlText,
            onValueChange = {
                urlText = it
                onBaseUrlTextChanged()
            },
            label = { Text("Base URL") },
            isError = state.baseUrlError != null,
            supportingText = state.baseUrlError?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { onSaveBaseUrl(urlText) }) {
                Text("Save")
            }
            OutlinedButton(
                onClick = onTestConnection,
                enabled = (state.tokenIsSet || state.sessionIsSet) &&
                    state.baseUrl.isNotBlank() &&
                    state.connectionTest != ConnectionTestUiState.Testing,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(if (state.connectionTest == ConnectionTestUiState.Testing) "Testing…" else "Test connection")
            }
        }
        ConnectionTestResultText(state.connectionTest)
    }
}

@Composable
private fun ConnectionTestResultText(result: ConnectionTestUiState) {
    when (result) {
        ConnectionTestUiState.Idle, ConnectionTestUiState.Testing -> Unit
        ConnectionTestUiState.Success -> Text(
            "✓ Connected — credential accepted",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        is ConnectionTestUiState.Failure -> Text(
            "✗ ${result.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
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

private enum class CredentialEditMode { NONE, TOKEN, LOGIN }

@Composable
private fun CredentialsSection(
    state: ConfigUiState,
    loginSucceeded: SharedFlow<Unit>,
    onSaveToken: (String) -> Unit,
    onLogin: (String, String) -> Unit,
    onClearCredentials: () -> Unit,
) {
    var mode by remember { mutableStateOf(CredentialEditMode.NONE) }
    var tokenText by remember { mutableStateOf("") }
    var usernameText by remember { mutableStateOf("") }
    var passwordText by remember { mutableStateOf("") }

    // One-shot: a sticky boolean read from state would re-fire every time this
    // screen is restored via Navigation Compose's saveState/restoreState.
    LaunchedEffect(loginSucceeded) {
        loginSucceeded.collect {
            mode = CredentialEditMode.NONE
            usernameText = ""
            passwordText = ""
        }
    }

    SectionCard(title = "VitalForge credentials") {
        Text(
            when {
                state.tokenIsSet -> "Signed in with an API token"
                state.sessionIsSet -> "Signed in via username/password"
                else -> "Not signed in"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        when (mode) {
            CredentialEditMode.NONE -> CredentialModeButtons(
                credentialIsSet = state.tokenIsSet || state.sessionIsSet,
                onUseToken = { mode = CredentialEditMode.TOKEN },
                onLogIn = { mode = CredentialEditMode.LOGIN },
                onClear = onClearCredentials,
            )
            CredentialEditMode.TOKEN -> TokenEditForm(
                tokenText = tokenText,
                onTokenTextChanged = { tokenText = it },
                onSave = {
                    onSaveToken(tokenText)
                    tokenText = ""
                    mode = CredentialEditMode.NONE
                },
                onCancel = { mode = CredentialEditMode.NONE; tokenText = "" },
            )
            CredentialEditMode.LOGIN -> LoginEditForm(
                usernameText = usernameText,
                passwordText = passwordText,
                errorMessage = state.loginError,
                isLoggingIn = state.isLoggingIn,
                onUsernameChanged = { usernameText = it },
                onPasswordChanged = { passwordText = it },
                onSignIn = { onLogin(usernameText, passwordText) },
                onCancel = { mode = CredentialEditMode.NONE; usernameText = ""; passwordText = "" },
            )
        }
    }
}

@Composable
private fun CredentialModeButtons(
    credentialIsSet: Boolean,
    onUseToken: () -> Unit,
    onLogIn: () -> Unit,
    onClear: () -> Unit,
) {
    Row(modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = onUseToken) { Text("Use a token") }
        OutlinedButton(onClick = onLogIn, modifier = Modifier.padding(start = 8.dp)) { Text("Log in") }
        if (credentialIsSet) {
            TextButton(onClick = onClear, modifier = Modifier.padding(start = 8.dp)) { Text("Clear") }
        }
    }
}

@Composable
private fun TokenEditForm(
    tokenText: String,
    onTokenTextChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    OutlinedTextField(
        value = tokenText,
        onValueChange = onTokenTextChanged,
        label = { Text("API token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
    Row(modifier = Modifier.padding(top = 8.dp)) {
        Button(onClick = onSave) { Text("Save token") }
        TextButton(onClick = onCancel, modifier = Modifier.padding(start = 8.dp)) { Text("Cancel") }
    }
}

@Composable
private fun LoginEditForm(
    usernameText: String,
    passwordText: String,
    errorMessage: String?,
    isLoggingIn: Boolean,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
) {
    OutlinedTextField(
        value = usernameText,
        onValueChange = onUsernameChanged,
        label = { Text("Username") },
        singleLine = true,
        enabled = !isLoggingIn,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
    OutlinedTextField(
        value = passwordText,
        onValueChange = onPasswordChanged,
        label = { Text("Password") },
        singleLine = true,
        enabled = !isLoggingIn,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
    errorMessage?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Row(modifier = Modifier.padding(top = 8.dp)) {
        Button(onClick = onSignIn, enabled = !isLoggingIn) {
            Text(if (isLoggingIn) "Signing in…" else "Sign in")
        }
        TextButton(
            onClick = onCancel,
            enabled = !isLoggingIn,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("Cancel") }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // UI states mirror the registration state machine directly.
private fun RegisteredScaleSection(
    pairedDeviceAddress: String?,
    registeredUserIndex: Int?,
    registration: ScaleRegistrationUiState,
    onRegister: (Boolean) -> Unit,
    onLinkExisting: (String, String, String) -> Unit,
    onReRegister: (String) -> Unit,
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
        if (registeredUserIndex == null) {
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { showWarning = true }, enabled = !busy) {
                    Text("Register scale")
                }
                OutlinedButton(
                    onClick = { showExisting = true },
                    enabled = !busy,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Use existing")
                }
            }
        } else if (pairedDeviceAddress != null) {
            OutlinedButton(
                onClick = { showWarning = true },
                enabled = !busy,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Re-register with the scale")
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
                    if (replacing) onReRegister(requireNotNull(pairedDeviceAddress)) else onRegister(false)
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

@Composable
@Suppress("LongMethod") // Document launchers must retain the same remembered transfer state.
private fun SettingsTransferSection(
    onExport: suspend (String) -> Result<ByteArray>,
    onImport: suspend (ByteArray, String) -> Result<Unit>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var dialogMode by remember { mutableStateOf<SettingsDialogMode?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) {
            scope.launch {
                val written = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
                    }
                }
                message = if (written.isSuccess) "Settings backup exported." else "Could not write the backup file."
            }
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                pendingImport = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)!!.use(InputStream::readSettingsBackup)
                    }
                }.getOrElse {
                    message = "Could not read the selected file."
                    null
                }
                if (pendingImport != null) dialogMode = SettingsDialogMode.IMPORT
            }
        }
    }

    SectionCard(title = "Settings backup") {
        Text(
            "Export or restore server, credentials, preferences, and scale registration. " +
                "The file is encrypted with a passphrase you choose.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { dialogMode = SettingsDialogMode.EXPORT }) { Text("Export") }
            OutlinedButton(
                onClick = { openDocument.launch(arrayOf("application/octet-stream", "application/json", "*/*")) },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Import") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    }

    dialogMode?.let { mode ->
        PassphraseDialog(
            confirmPassphrase = mode == SettingsDialogMode.EXPORT,
            onDismiss = { dialogMode = null },
            onConfirm = { passphrase ->
                dialogMode = null
                scope.launch {
                    if (mode == SettingsDialogMode.EXPORT) {
                        onExport(passphrase).fold(
                            onSuccess = {
                                pendingExport = it
                                createDocument.launch("bascule-settings.bascule")
                            },
                            onFailure = { message = it.message ?: "Could not create settings backup." },
                        )
                    } else {
                        val bytes = pendingImport
                        pendingImport = null
                        if (bytes != null) {
                            onImport(bytes, passphrase).fold(
                                onSuccess = { message = "Settings restored." },
                                onFailure = { message = "Import failed. Check the file and passphrase." },
                            )
                        }
                    }
                }
            },
        )
    }
}

private enum class SettingsDialogMode { EXPORT, IMPORT }

private fun InputStream.readSettingsBackup(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= SettingsBackupCodec.MAX_BACKUP_BYTES) { "Settings backup is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

@Composable
private fun PassphraseDialog(
    confirmPassphrase: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val valid = passphrase.length >= SettingsBackupCodec.MIN_PASSPHRASE_LENGTH &&
        (!confirmPassphrase || passphrase == confirmation)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmPassphrase) "Encrypt settings backup" else "Unlock settings backup") },
        text = {
            Column {
                Text("Use at least 8 characters. This passphrase cannot be recovered.")
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (confirmPassphrase) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Confirm passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = valid) {
                Text(if (confirmPassphrase) "Export" else "Import")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
