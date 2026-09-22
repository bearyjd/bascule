package com.ventouxlabs.bascule.ui.config

import com.ventouxlabs.bascule.ble.RegistrationPhase
import com.ventouxlabs.bascule.ble.ScaleRegistrar
import com.ventouxlabs.bascule.ble.ScaleRegistrationResult
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.ScaleProfileCodec
import com.ventouxlabs.bascule.data.ScaleProfileStore
import com.ventouxlabs.bascule.ui.ScaleRegistrationUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The two routes that link this app to a scale — the BLE registration
 * handshake and restoring a known mapping by hand — and the registration
 * state they drive. Split out of [com.ventouxlabs.bascule.ui.ConfigViewModel],
 * which keeps thin delegating methods and folds [state] into its `uiState`.
 *
 * [scope] is the owning ViewModel's `viewModelScope`: both routes do their
 * synchronous guards and validation *before* launching, and the busy guard in
 * [startScaleRegistration] rests on exactly that shape, so the launch has to
 * happen here rather than in the ViewModel's wrapper.
 *
 * [onConsentChanged] is invoked where the ViewModel used to bump its own
 * consent version — `ConsentStore` has no Flow, so this is what tells the
 * ViewModel to re-read the registered user index after a registration lands.
 */
internal class ScaleLinkingController(
    private val scope: CoroutineScope,
    private val scaleRegistrar: ScaleRegistrar?,
    private val scaleProfileStore: ScaleProfileStore?,
    private val consentStore: ConsentStore,
    private val configStore: ConfigStore,
    private val rearmScanner: (suspend () -> Unit)?,
    private val ioDispatcher: CoroutineDispatcher,
    private val onConsentChanged: () -> Unit,
) {

    private val _scaleRegistration = MutableStateFlow<ScaleRegistrationUiState>(ScaleRegistrationUiState.Idle)

    val state: StateFlow<ScaleRegistrationUiState> = _scaleRegistration.asStateFlow()

    /** Back to [ScaleRegistrationUiState.Idle] — a settings import replaces whatever was being reported. */
    fun reset() {
        _scaleRegistration.value = ScaleRegistrationUiState.Idle
    }

    /**
     * O-08.5: re-registering may consume one of the scale's 8 profile slots
     * (§8.8, HW-26) — the caller must have already shown that warning and
     * gotten explicit confirmation before this runs. The registrar preserves
     * the working credential until the scale is actually found, then clears
     * it immediately before the new handshake so a failed scan loses nothing.
     */
    fun reRegister() {
        startScaleRegistration(forceNew = true)
    }

    fun startScaleRegistration(forceNew: Boolean = false) {
        if (_scaleRegistration.value == ScaleRegistrationUiState.Scanning ||
            _scaleRegistration.value == ScaleRegistrationUiState.Connecting
        ) {
            return
        }
        val registrar = scaleRegistrar
        if (registrar == null) {
            _scaleRegistration.value = ScaleRegistrationUiState.Failure("Scale registration is unavailable")
            return
        }
        scope.launch {
            val result = registrar.register(forceNew) { phase ->
                _scaleRegistration.value = when (phase) {
                    RegistrationPhase.SCANNING -> ScaleRegistrationUiState.Scanning
                    RegistrationPhase.CONNECTING -> ScaleRegistrationUiState.Connecting
                }
            }
            when (result) {
                is ScaleRegistrationResult.Success -> onRegistrationSucceeded(result.address, result.scaleIndex)
                is ScaleRegistrationResult.Failure ->
                    _scaleRegistration.value = ScaleRegistrationUiState.Failure(result.message)
            }
        }
    }

    /** Restores a known BF720 mapping without consuming another one of its eight slots. */
    fun linkExistingScale(address: String, scaleIndex: String, consentCode: String) {
        val normalizedAddress = address.trim().uppercase()
        val index = scaleIndex.toIntOrNull()
        val code = consentCode.toIntOrNull()
        when {
            !BLUETOOTH_ADDRESS.matches(normalizedAddress) ->
                _scaleRegistration.value = ScaleRegistrationUiState.Failure("Enter a valid Bluetooth address")
            index !in SCALE_INDEX_RANGE ->
                _scaleRegistration.value = ScaleRegistrationUiState.Failure(
                    "User slot must be between ${SCALE_INDEX_RANGE.first} and ${SCALE_INDEX_RANGE.last}",
                )
            code !in CONSENT_CODE_RANGE ->
                _scaleRegistration.value = ScaleRegistrationUiState.Failure(
                    "Consent code must be between ${CONSENT_CODE_RANGE.first} and ${CONSENT_CODE_RANGE.last}",
                )
            else -> scope.launch {
                val scaleIndexValue = requireNotNull(index)
                // Encrypted-prefs write — same seam as writeCredentials.
                withContext(ioDispatcher) {
                    consentStore.save(normalizedAddress, ScaleCredential(scaleIndexValue, requireNotNull(code)))
                }
                configStore.savePairedDeviceAddress(normalizedAddress)
                onRegistrationSucceeded(normalizedAddress, scaleIndexValue)
            }
        }
    }

    /**
     * The tail both registration routes share — the BLE handshake and
     * [linkExistingScale]. Extracted because enabling capture in only one would
     * make registering via the scale work while linking by hand silently did
     * not, with nothing to report the difference.
     *
     * Capture is enabled here rather than defaulted on in `ConfigStore`: a bare
     * default would arm background scanning for someone who never asked, whereas
     * completing a registration is an unambiguous statement of intent. The Scale
     * screen's toggle still turns it back off.
     */
    private suspend fun onRegistrationSucceeded(address: String, scaleIndex: Int) {
        activateLinkedProfile(address, scaleIndex)
        configStore.saveAutomaticCaptureEnabled(true)
        rearmScanner?.invoke()
        onConsentChanged()
        _scaleRegistration.value = ScaleRegistrationUiState.Success(address, scaleIndex)
    }

    /**
     * A profile the registry creates for an already-active device is stored
     * inactive, and only the active profile is scanned and captured for — so
     * without this, establishing a second scale reports success and then
     * silently never captures. The user hand-entered — or just registered —
     * this mapping; that is the one they mean to use.
     */
    private suspend fun activateLinkedProfile(address: String, scaleIndex: Int) {
        val store = scaleProfileStore ?: return
        withContext(ioDispatcher) {
            store.profiles.value
                .firstOrNull { it.deviceAddress.equals(address, true) && it.scaleIndex == scaleIndex }
                ?.takeUnless { it.active }
                ?.let { store.setActive(it.id) }
        }
    }

    private companion object {
        private val BLUETOOTH_ADDRESS = ScaleProfileCodec.BLUETOOTH_ADDRESS
        private val SCALE_INDEX_RANGE = SigWeightProfile.SCALE_INDEX_RANGE
        private val CONSENT_CODE_RANGE = SigWeightProfile.CONSENT_CODE_RANGE
    }
}
