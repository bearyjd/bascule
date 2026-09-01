package com.ventouxlabs.bascule.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ScaleProfile
import com.ventouxlabs.bascule.data.ScaleProfileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScaleUiState(
    val profiles: List<ScaleProfile> = emptyList(),
    val automaticCaptureEnabled: Boolean = false,
    val alwaysOnBridging: Boolean = false,
    val pendingDeliveries: Int = 0,
    val lastCaptureMillis: Long? = null,
    val diagnostic: String? = null,
    /**
     * True only for `stateIn`'s seed value. Every real emission clears it, so
     * the screen can tell "no profiles registered" apart from "the registry
     * has not been read yet" — without it the empty state flashes on every
     * open before the first combine lands.
     */
    val isLoading: Boolean = true,
    /** True while a [ScaleViewModel.weighNow] window is running. */
    val weighNowActive: Boolean = false,
)

private data class ScaleCaptureSnapshot(
    val automaticCaptureEnabled: Boolean,
    val alwaysOnBridging: Boolean,
    val pendingDeliveries: Int,
    val lastCaptureMillis: Long?,
    val diagnostic: String?,
)

/**
 * The seam between this ViewModel and [BridgeForegroundService]'s Android
 * lifecycle. Without it the ViewModel builds `Intent`s against a captured
 * `Application`, so bridging can only be exercised in a test by passing an
 * ad-hoc lambda resembling nothing in production — the same reason
 * `ConfigStore`, `ConsentStore` and `DeliveryTrigger` are interfaces.
 */
interface BridgeServiceController {
    fun start()

    /** Same underlying scan, bounded: the service stops itself once [durationMillis] elapses. */
    fun startBounded(durationMillis: Long)
    fun stop()
}

class ScaleViewModel(
    private val config: ConfigStore,
    private val profiles: ScaleProfileStore,
    dao: ReadingDao,
    private val onArm: suspend () -> Boolean,
    private val onDisarm: () -> Unit,
    private val bridgeService: BridgeServiceController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    /**
     * Seeded from [ScaleProfileStore.readFailure] rather than `null`: a
     * corrupted registry blob is quarantined, not lost (see
     * [com.ventouxlabs.bascule.data.EncryptedScaleProfileStore]), but without
     * this the only user-visible sign that it happened is registrations that
     * are silently empty. `readFailure` is fixed at construction, so this is a
     * one-time notice — the next toggle interaction clears it like any other
     * diagnostic message.
     */
    private val diagnostic = MutableStateFlow(if (profiles.readFailure != null) REGISTRY_UNREADABLE_MESSAGE else null)

    /** combine() tops out at 5 typed flows per call — this nests to fit the sixth. */
    private val captureState = combine(
        config.automaticCaptureEnabled,
        config.alwaysOnBridging,
        dao.observePendingCount(),
        dao.observeLastScaleCapture(),
        diagnostic,
    ) { automaticCapture, alwaysOn, pending, lastCapture, message ->
        ScaleCaptureSnapshot(automaticCapture, alwaysOn, pending, lastCapture, message)
    }

    private val mutableWeighNowActive = MutableStateFlow(false)
    private var weighNowJob: Job? = null

    val uiState: StateFlow<ScaleUiState> = combine(
        profiles.profiles,
        captureState,
        mutableWeighNowActive,
    ) { all, capture, weighNowActive ->
        ScaleUiState(
            profiles = all,
            automaticCaptureEnabled = capture.automaticCaptureEnabled,
            alwaysOnBridging = capture.alwaysOnBridging,
            pendingDeliveries = capture.pendingDeliveries,
            lastCaptureMillis = capture.lastCaptureMillis,
            diagnostic = capture.diagnostic,
            isLoading = false,
            weighNowActive = weighNowActive,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), ScaleUiState())

    fun setAutomaticCapture(enabled: Boolean) = viewModelScope.launch {
        if (enabled && profiles.activeProfile.value == null) {
            diagnostic.value = "Link or register a profile before enabling automatic capture."
            return@launch
        }
        config.saveAutomaticCaptureEnabled(enabled)
        if (enabled) {
            diagnostic.value = if (onArm()) {
                null
            } else {
                "Background scan could not be armed. Check Bluetooth and permissions."
            }
        } else {
            onDisarm()
            diagnostic.value = null
        }
    }

    fun setAlwaysOnBridging(enabled: Boolean) = viewModelScope.launch {
        config.saveAlwaysOnBridging(enabled)
        if (enabled) bridgeService.start() else bridgeService.stop()
    }

    /**
     * Only the profile write goes to IO — [onArm] reaches `BluetoothLeScanner`
     * and stays on the dispatcher it has always run on.
     */
    fun setActive(profileId: String) = viewModelScope.launch {
        withContext(ioDispatcher) { profiles.setActive(profileId) }
        if (config.automaticCaptureEnabled.first()) onArm()
    }

    /** [ScaleProfileStore.saveProfile] is a synchronous encrypted-prefs `commit()` — never the caller's thread. */
    fun rename(profile: ScaleProfile, label: String) {
        val safe = label.trim().take(MAX_LABEL_LENGTH)
        if (safe.isEmpty()) return
        viewModelScope.launch {
            withContext(ioDispatcher) { profiles.saveProfile(profile.copy(label = safe)) }
        }
    }

    /**
     * Local-only: the BF720 keeps its own copy of the slot until it's
     * overwritten or reset. Deleting the active profile leaves none active —
     * [ScaleScanner.arm] and [setAutomaticCapture]'s diagnostic already cover
     * that state, so no extra guard is needed here.
     */
    fun delete(profile: ScaleProfile) = viewModelScope.launch {
        withContext(ioDispatcher) { profiles.deleteProfile(profile.id) }
    }

    /**
     * A bounded, foreground-triggered fast scan for "I'm about to step on the
     * scale right now" — orthogonal to [setAutomaticCapture]'s LOW_POWER
     * background path and [setAlwaysOnBridging]'s persistent one. Re-entrant
     * calls while a window is already running are ignored rather than
     * restarting the timer, so a double-tap doesn't quietly extend it.
     */
    fun weighNow() {
        if (weighNowJob != null) return
        weighNowJob = viewModelScope.launch {
            if (config.alwaysOnBridging.first()) {
                diagnostic.value = "Always-on foreground fallback is already scanning — nothing more to start."
                return@launch
            }
            mutableWeighNowActive.value = true
            bridgeService.startBounded(WEIGH_NOW_DURATION_MILLIS)
            delay(WEIGH_NOW_DURATION_MILLIS)
            mutableWeighNowActive.value = false
        }.also { job -> job.invokeOnCompletion { weighNowJob = null } }
    }

    /** No-op with no window running — the service self-stops on its own once its window elapses regardless. */
    fun cancelWeighNow() {
        if (!mutableWeighNowActive.value) return
        weighNowJob?.cancel()
        bridgeService.stop()
        mutableWeighNowActive.value = false
    }

    companion object {
        private const val MAX_LABEL_LENGTH = 40
        private const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L

        /** Past `SessionBudget.HARD_SESSION_CEILING` (90s), with margin for discovery time ahead of that session. */
        const val WEIGH_NOW_DURATION_MILLIS = 120_000L
        const val REGISTRY_UNREADABLE_MESSAGE =
            "Your saved scale registrations could not be read and were reset. Re-link or re-register your scale."

        fun factory(app: BasculeApplication) = viewModelFactory {
            initializer {
                ScaleViewModel(
                    app.configStore, app.scaleProfileStore, app.database.readingDao(),
                    onArm = app.scaleScanner::arm, onDisarm = app.scaleScanner::disarm,
                    bridgeService = app.bridgeServiceController,
                )
            }
        }
    }
}
