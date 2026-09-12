package com.ventouxlabs.bascule.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ScaleProfile
import com.ventouxlabs.bascule.diagnostics.CaptureAttemptLog
import com.ventouxlabs.bascule.diagnostics.LastCaptureAttempt
import com.ventouxlabs.bascule.service.ScanEnqueueCooldown
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
    /**
     * The most recent automatic capture attempt, successful or not. Distinct
     * from [lastCaptureMillis], which only ever moves on success: the gap
     * between the two is the whole point, because an attempt that reached the
     * scale and came back empty used to be indistinguishable from no attempt.
     */
    val lastAttempt: LastCaptureAttempt? = null,
    /**
     * `BasculeApplication.onCreate` could not bring the always-on bridge up,
     * or one of its startup steps threw. Both flows existed with no screen
     * rendering them, so a phone that silently never started capturing looked
     * identical to one that had.
     */
    val bridgeStartFailed: Boolean = false,
    val startupFailure: String? = null,
)

private data class StartupProblems(val bridgeStartFailed: Boolean, val startupFailure: String?)

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
/**
 * Paired acquire/release, not start/stop: the service runs while anyone wants
 * it and stops when the last caller lets go, so no caller has to reason about
 * whether *another* one still needs it. [start]/[stop] are the always-on
 * toggle's pair, [startBounded]/[cancelBounded] are "Weigh now"'s.
 */
interface BridgeServiceController {
    fun start()

    /** Same underlying scan, bounded: the window releases itself once [durationMillis] elapses. */
    fun startBounded(durationMillis: Long)

    /**
     * Give up the always-on claim. The service keeps running if a "Weigh now"
     * window still holds one.
     */
    fun stop()

    /**
     * End a "Weigh now" window early. A no-op when none is running, and it
     * cannot stop a service the always-on toggle is holding open.
     */
    fun cancelBounded()

    /**
     * Re-register the running service's scan against a replacement Bluetooth
     * stack, in place. A no-op when nothing owns the bridge — deliberately, so
     * this can never bring up one the user has switched off. Distinct from
     * [stop] + [start] because `Context.stopService` is asynchronous: the start
     * can land on the still-live instance, which re-registers nothing.
     */
    fun rearmScan()
}

class ScaleViewModel(
    private val config: ConfigStore,
    private val profiles: ScaleProfileStore,
    dao: ReadingDao,
    private val onArm: suspend () -> Boolean,
    private val onDisarm: () -> Unit,
    private val bridgeService: BridgeServiceController,
    captureAttempts: CaptureAttemptLog,
    /**
     * Drops any retry throttle held against the active scale. "Weigh now"
     * means the user is standing on it *right now*, and a backoff earned by
     * earlier idle sessions must not be what delays that — see
     * `ScanEnqueueCooldown.clear`.
     */
    private val clearCaptureThrottle: () -> Unit = {},
    bridgeStartFailed: StateFlow<Boolean> = MutableStateFlow(false),
    startupFailure: StateFlow<Throwable?> = MutableStateFlow(null),
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

    private val startupProblems = combine(bridgeStartFailed, startupFailure) { bridge, failure ->
        StartupProblems(bridge, failure?.let { it.message ?: it::class.simpleName })
    }

    val uiState: StateFlow<ScaleUiState> = combine(
        profiles.profiles,
        captureState,
        mutableWeighNowActive,
        captureAttempts.last,
        startupProblems,
    ) { all, capture, weighNowActive, lastAttempt, startup ->
        ScaleUiState(
            profiles = all,
            automaticCaptureEnabled = capture.automaticCaptureEnabled,
            alwaysOnBridging = capture.alwaysOnBridging,
            pendingDeliveries = capture.pendingDeliveries,
            lastCaptureMillis = capture.lastCaptureMillis,
            diagnostic = capture.diagnostic,
            isLoading = false,
            weighNowActive = weighNowActive,
            lastAttempt = lastAttempt,
            bridgeStartFailed = startup.bridgeStartFailed,
            startupFailure = startup.startupFailure,
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
     * overwritten or reset. Deleting the *active* profile explicitly disarms
     * automatic capture — [ScaleScanner.arm] and [setAutomaticCapture]'s
     * diagnostic only guard *arming* with no active profile; neither runs
     * again once capture is already armed, so without this the config flag
     * and the LOW_POWER scan would both keep advertising a capture path that
     * can never fire again (devil's-advocate review, M-3).
     */
    fun delete(profile: ScaleProfile) = viewModelScope.launch {
        withContext(ioDispatcher) { profiles.deleteProfile(profile.id) }
        if (profile.active && config.automaticCaptureEnabled.first()) {
            config.saveAutomaticCaptureEnabled(false)
            onDisarm()
            diagnostic.value = "Automatic capture turned off — its profile was removed."
        }
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
        val job = viewModelScope.launch {
            if (profiles.activeProfile.value == null) {
                diagnostic.value = "Link or register a profile before using Weigh now."
                return@launch
            }
            // Always-on already holds a scan open, so there is no service to
            // start — but that scan gates on the same cooldown a bounded start
            // clears, and a scale that advertises continuously (the BF720 does)
            // has usually earned a long backoff by the time anyone steps on.
            // Clearing it is the whole value of the button in this mode.
            val alwaysOn = config.alwaysOnBridging.first()
            withContext(ioDispatcher) { clearCaptureThrottle() }
            mutableWeighNowActive.value = true
            if (alwaysOn) {
                diagnostic.value = "Always-on is already scanning — retry throttle cleared, step on now."
            } else {
                bridgeService.startBounded(WEIGH_NOW_DURATION_MILLIS)
            }
            delay(WEIGH_NOW_DURATION_MILLIS)
            mutableWeighNowActive.value = false
        }
        weighNowJob = job
        // Assigned above, registered after: a coroutine that completes
        // before `launch` returns would otherwise run this callback before
        // the outer `weighNowJob = job` assignment lands, leaving the field
        // stuck non-null forever — the identity check is what makes a stale
        // completion from an earlier job a no-op against the current one
        // (devil's-advocate review, L-1).
        job.invokeOnCompletion { if (weighNowJob === job) weighNowJob = null }
    }

    /**
     * No window running is a no-op — the window releases itself once it elapses
     * regardless.
     *
     * This used to read `alwaysOnBridging` first and stop the service only if
     * it was off, because "Always-on foreground fallback" can be switched on
     * *during* a window and an unconditional `stop()` would have killed the
     * scan the user separately asked to keep running (devil's-advocate review,
     * H-2). [BridgeServiceController.cancelBounded] releases only this window's
     * own claim, so that check — and the coroutine it needed to read config —
     * is now structurally unnecessary rather than merely correct.
     */
    fun cancelWeighNow() {
        if (!mutableWeighNowActive.value) return
        weighNowJob?.cancel()
        mutableWeighNowActive.value = false
        bridgeService.cancelBounded()
    }

    companion object {
        private const val MAX_LABEL_LENGTH = 40
        private const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L

        /**
         * Only the *scan* is bounded by this; the session it enqueues runs in
         * `ScaleSessionWorker` for its own budget regardless. So this needs to
         * outlast scan-to-enqueue latency and give the user time to step on,
         * not contain a whole session — which is now minutes long.
         */
        const val WEIGH_NOW_DURATION_MILLIS = 120_000L
        const val REGISTRY_UNREADABLE_MESSAGE =
            "Your saved scale registrations could not be read and were reset. Re-link or re-register your scale."

        fun factory(app: BasculeApplication) = viewModelFactory {
            initializer {
                ScaleViewModel(
                    app.configStore, app.scaleProfileStore, app.database.readingDao(),
                    onArm = app.scaleScanner::arm, onDisarm = app.scaleScanner::disarm,
                    bridgeService = app.bridgeServiceController,
                    captureAttempts = app.captureAttemptLog,
                    clearCaptureThrottle = {
                        app.scaleProfileStore.activeProfile.value?.deviceAddress
                            ?.let { ScanEnqueueCooldown(app).clear(it) }
                    },
                    bridgeStartFailed = app.alwaysOnBridgingStartFailed,
                    startupFailure = app.startupFailure,
                )
            }
        }
    }
}
