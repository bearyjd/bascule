package com.ventouxlabs.bascule.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            UnitSection(
                state = state,
                onUnitChanged = viewModel::saveDisplayUnit,
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
            SettingsTransferSection(
                onExport = viewModel::exportSettings,
                onImport = viewModel::importSettings,
            )
        }
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Column(modifier = Modifier.padding(top = 12.dp)) { content() }
        }
    }
}

/**
 * Everything [PermissionRequester] knows, as plain data rather than state
 * private to one composable. Hoisted so a screen other than Settings can name
 * the permission that is actually missing — `ScaleViewModel` currently reports
 * only the generic "Background scan could not be armed. Check Bluetooth and
 * permissions." because this knowledge was reachable from nowhere else.
 */
internal data class PermissionUiState(
    val pendingPermissions: List<String> = emptyList(),
    val awaitingBackgroundLocation: Boolean = false,
    val needsLocationRationale: Boolean = false,
    val backgroundLocationRequiresSettings: Boolean = false,
) {
    val allGranted: Boolean get() = pendingPermissions.isEmpty() && !awaitingBackgroundLocation
}

internal fun PermissionRequester.uiState() = PermissionUiState(
    pendingPermissions = firstDialogPermissions(),
    awaitingBackgroundLocation = secondDialogPermission() != null,
    needsLocationRationale = needsLocationRationale(),
    backgroundLocationRequiresSettings = backgroundLocationRequiresSettings(),
)

/**
 * Seeded from [requester] rather than from a blank default, so a returning
 * user on API 29/30 who granted fine location in a prior session composes
 * straight into the awaiting-background-location state — initializing that to
 * false would hide the second dialog forever, since no other path sets it true.
 *
 * Re-derived on every `ON_RESUME` because permissions can be granted or
 * revoked in system Settings while this screen is backgrounded. The returned
 * state is mutable so a permission-result callback can refresh it too.
 */
@Composable
internal fun rememberPermissionUiState(requester: PermissionRequester): MutableState<PermissionUiState> {
    val state = remember(requester) { mutableStateOf(requester.uiState()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, requester) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = requester.uiState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

@Composable
@Suppress("LongMethod") // Permission launchers and their SDK-specific UI are intentionally colocated.
private fun PermissionSection(requester: PermissionRequester) {
    val permissionState = rememberPermissionUiState(requester)
    val permissions = permissionState.value

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionState.value = requester.uiState()
    }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Re-derive from the requester rather than hardcoding false: a denial
        // must not permanently hide this prompt, since the user can still
        // grant it later from system settings and the app should offer it
        // again on the next composition.
        permissionState.value = requester.uiState()
    }

    if (permissions.allGranted) return

    SectionCard(title = "Permissions needed") {
        if (permissions.needsLocationRationale && permissions.pendingPermissions.isNotEmpty()) {
            Text(
                "Bascule asks for location because Android requires it to detect nearby Bluetooth " +
                    "devices on this version of Android — it is never used to track where you are.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        if (permissions.pendingPermissions.isNotEmpty()) {
            Button(onClick = { launcher.launch(permissions.pendingPermissions.toTypedArray()) }) {
                Text("Grant permissions")
            }
        } else if (permissions.awaitingBackgroundLocation) {
            Text(
                "One more: allow background location so weigh-ins are detected even when Bascule " +
                    "isn't open.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (permissions.backgroundLocationRequiresSettings) {
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

/**
 * `V2_BODY_COMP` is withheld because `V2Shaper`'s body-composition field names
 * are placeholders: 00-design.md §4.2 requires them to come from VitalForge's
 * Track A contract doc, which has not landed. The shaper's own KDoc claims it
 * "is not selectable until that document lands" — this dropdown and
 * [com.ventouxlabs.bascule.ui.ConfigViewModel.importSettings]'s matching
 * field-skip gate are what make that true together; a settings import is a
 * second path into this same state and is gated identically. Delete both
 * filters when the doc lands and the names are pinned, alongside the
 * shaper's KDoc caveat.
 */
internal val selectableContractVersions: List<ContractVersion> =
    ContractVersion.entries.filterNot { it == ContractVersion.V2_BODY_COMP }

@Composable
private fun UnitSection(
    state: ConfigUiState,
    onUnitChanged: (WeightUnit) -> Unit,
) {
    SectionCard(title = "Units") {
        LabeledDropdown(
            label = "Weight unit",
            options = WeightUnit.entries,
            optionLabel = { it.name.lowercase().replaceFirstChar(Char::uppercase) },
            selected = state.displayUnit,
            onSelected = onUnitChanged,
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
        CredentialStatusText(state)
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

/**
 * `tokenIsSet`/`sessionIsSet` only mean a credential was *saved*, not that the
 * server still accepts it. Without the rejected branches this card claimed
 * "Signed in" while the History screen simultaneously said "VitalForge needs
 * your login again" — the same app state described two contradictory ways.
 * Found on the first real-hardware run against a six-day-stale session.
 */
@Composable
private fun CredentialStatusText(state: ConfigUiState) {
    Text(
        when {
            state.credentialRejected && state.tokenIsSet ->
                "API token was rejected — sign in again to send waiting weigh-ins"

            state.credentialRejected ->
                "Session expired — sign in again to send waiting weigh-ins"

            state.tokenIsSet -> "Signed in with an API token"
            state.sessionIsSet -> "Signed in via username/password"
            else -> "Not signed in"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.credentialRejected) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
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
@Suppress("LongMethod") // Document launchers must retain the same remembered transfer state.
private fun SettingsTransferSection(
    onExport: suspend (String) -> Result<ByteArray>,
    onImport: suspend (ByteArray, String) -> Result<ImportOutcome>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingImport by remember { mutableStateOf<ByteArray?>(null) }
    var dialogMode by remember { mutableStateOf<SettingsDialogMode?>(null) }
    var message by remember { mutableStateOf<TransferMessage?>(null) }

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
                val text = if (written.isSuccess) "Settings backup exported." else "Could not write the backup file."
                message = TransferMessage.Fixed(text)
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
                    message = TransferMessage.Fixed("Could not read the selected file.")
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
        message?.resolve()?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
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
                            onFailure = {
                                val text = it.message ?: "Could not create settings backup."
                                message = TransferMessage.Fixed(text)
                            },
                        )
                    } else {
                        val bytes = pendingImport
                        pendingImport = null
                        if (bytes != null) {
                            onImport(bytes, passphrase).fold(
                                onSuccess = { message = TransferMessage.ImportSucceeded(it) },
                                onFailure = {
                                    message = TransferMessage.Fixed("Import failed. Check the file and passphrase.")
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

private enum class SettingsDialogMode { EXPORT, IMPORT }

/**
 * A successful import is the one message whose wording depends on what the
 * import actually did, so it carries its [ImportOutcome] rather than a
 * pre-rendered string. Modelling it as a case rather than a second flag beside
 * the string makes "only one message can be showing" structural.
 */
private sealed interface TransferMessage {
    data class Fixed(val text: String) : TransferMessage
    data class ImportSucceeded(val outcome: ImportOutcome) : TransferMessage
}

private fun TransferMessage.resolve(): String = when (this) {
    is TransferMessage.Fixed -> text
    is TransferMessage.ImportSucceeded -> importSuccessMessage(outcome)
}

/**
 * A host-changing import parks the whole pending queue behind `BLOCKED_AUTH`
 * and deliberately declines to install the backup's own credential (see
 * [ImportOutcome]). Reporting only "Settings restored." left both facts
 * invisible: the user assumed delivery was healthy while every queued reading
 * was held, and would only find out by noticing the credentials card had gone
 * back to "Not signed in".
 */
internal fun importSuccessMessage(outcome: ImportOutcome): String = when (outcome) {
    ImportOutcome.APPLIED -> "Settings restored."
    ImportOutcome.APPLIED_WITHOUT_CREDENTIAL_AFTER_HOST_CHANGE ->
        "Settings restored, but the backup uses a different server — your credentials were cleared " +
            "and readings are on hold. Sign in again to resume delivery."
}

@Composable
private fun PassphraseDialog(
    confirmPassphrase: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val valid = isPassphraseValid(passphrase, confirmation, confirmRequired = confirmPassphrase)
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
