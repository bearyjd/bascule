package com.ventouxlabs.bascule.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.service.BridgeForegroundService
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ScaleProfile
import com.ventouxlabs.bascule.data.ScaleProfileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
)

private data class CaptureState(
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
    private val diagnostic = MutableStateFlow(profiles.readFailure?.let { REGISTRY_UNREADABLE_MESSAGE })

    /** combine() tops out at 5 typed flows per call — this nests to fit the sixth. */
    private val captureState = combine(
        config.automaticCaptureEnabled,
        config.alwaysOnBridging,
        dao.observePendingCount(),
        dao.observeLastScaleCapture(),
        diagnostic,
    ) { automaticCapture, alwaysOn, pending, lastCapture, message ->
        CaptureState(automaticCapture, alwaysOn, pending, lastCapture, message)
    }

    val uiState: StateFlow<ScaleUiState> = combine(
        profiles.profiles,
        captureState,
    ) { all, capture ->
        ScaleUiState(
            profiles = all,
            automaticCaptureEnabled = capture.automaticCaptureEnabled,
            alwaysOnBridging = capture.alwaysOnBridging,
            pendingDeliveries = capture.pendingDeliveries,
            lastCaptureMillis = capture.lastCaptureMillis,
            diagnostic = capture.diagnostic,
            isLoading = false,
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

    companion object {
        private const val MAX_LABEL_LENGTH = 40
        private const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
        const val REGISTRY_UNREADABLE_MESSAGE =
            "Your saved scale registrations could not be read and were reset. Re-link or re-register your scale."

        fun factory(app: BasculeApplication) = viewModelFactory {
            initializer {
                ScaleViewModel(
                    app.configStore, app.scaleProfileStore, app.database.readingDao(),
                    onArm = app.scaleScanner::arm, onDisarm = app.scaleScanner::disarm,
                    bridgeService = AndroidBridgeServiceController(app),
                )
            }
        }
    }
}

private class AndroidBridgeServiceController(private val context: Context) : BridgeServiceController {
    override fun start() {
        ContextCompat.startForegroundService(context, intent())
    }

    override fun stop() {
        context.stopService(intent())
    }

    private fun intent() = Intent(context, BridgeForegroundService::class.java)
}
