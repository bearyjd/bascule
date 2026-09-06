package com.ventouxlabs.bascule.ble.session

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.R
import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder
import com.ventouxlabs.bascule.diagnostics.CaptureOutcome
import com.ventouxlabs.bascule.diagnostics.attentionTransition
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import com.ventouxlabs.bascule.runNonCancelling
import com.ventouxlabs.bascule.service.CaptureAttentionNotifier
import com.ventouxlabs.bascule.service.CooldownDisposition
import com.ventouxlabs.bascule.service.ScanEnqueueCooldown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ScaleSessionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    /**
     * Every exit from here settles the scan cooldown this session's
     * advertisement claimed. That is the whole point of the indirection through
     * [SessionExit]: the exits that matter most are the early ones — a stale
     * advertisement above all — and a release hooked into [resultFor] alone
     * would leave exactly those paths holding the address down for the full
     * five-minute window, which is the lockout the split exists to end.
     */
    override suspend fun doWork(): Result {
        // Nothing to settle without an address, and nothing claimed one either.
        val address = inputData.getString(KEY_ADDRESS) ?: return Result.failure()
        // `finally`, not a plain sequence: a session that throws or is
        // cancelled is exactly a session that would otherwise leave the address
        // locked for the full window with no reading to show for it, which is
        // the defect this whole path exists to close. `NonCancellable` because
        // settling is two disk writes, and a cancelled worker cannot suspend.
        var reason = SessionExitReason.UNEXPECTED_ERROR
        try {
            val exit = attempt(address)
            reason = exit.reason
            return exit.result
        } finally {
            withContext(NonCancellable) {
                settleCooldown(address, cooldownDispositionFor(reason))
                recordAttempt(reason)
            }
        }
    }

    private suspend fun attempt(address: String): SessionExit {
        val seenAt = inputData.getLong(KEY_SEEN_AT, 0L)
        if (seenAt <= 0L || System.currentTimeMillis() - seenAt > STALENESS_ABORT_MILLIS) {
            // Not a scale problem: the advertisement was live when it was
            // enqueued and went stale waiting for a dispatch slot. Releasing is
            // what lets the next one in while the user is still standing there.
            Log.i(TAG, "skipped: advertisement went stale before this worker was dispatched")
            return SessionExit(Result.success(), SessionExitReason.STALE_ADVERTISEMENT)
        }
        if (!hasConnectPermission()) {
            Log.w(TAG, "skipped: BLUETOOTH_CONNECT is not granted")
            return SessionExit(Result.failure(), SessionExitReason.PERMISSION_DENIED)
        }
        return runSession(address)
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    private suspend fun runSession(address: String): SessionExit {
        val app = applicationContext as BasculeApplication
        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Log.w(TAG, "skipped: this device has no Bluetooth adapter")
            return SessionExit(Result.failure(), SessionExitReason.NO_ADAPTER)
        }
        if (!adapter.isEnabled) {
            Log.i(TAG, "skipped: Bluetooth is switched off")
            return SessionExit(Result.retry(), SessionExitReason.ADAPTER_DISABLED)
        }
        val profile = app.scaleProfileStore.activeProfile.value
        if (profile == null || !profile.deviceAddress.equals(address, true)) {
            Log.i(TAG, "skipped: the advertisement is not from the active scale profile")
            return SessionExit(Result.success(), SessionExitReason.NOT_ACTIVE_PROFILE)
        }
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            Log.w(TAG, "skipped: the active profile's address could not be resolved")
            return SessionExit(Result.failure(), SessionExitReason.UNRESOLVABLE_DEVICE)
        }
        // Last, after every viability gate above: the "capturing" notification
        // is a promise to the user that a session is about to happen, and a
        // stale advertisement for a non-active address used to show it and then
        // silently no-op.
        //
        // retry(), on the same reasoning as ADAPTER_OFF below: a refused
        // foreground start is a statement about this moment, not this device.
        // Best-effort in the same way DecodeFailure's is — WorkManager's 10s
        // backoff floor often lands past STALENESS_ABORT_MILLIS, at which point
        // the retry returns success() without touching the radio.
        if (!enterForeground(app)) {
            Log.w(TAG, "skipped: the platform refused a foreground start")
            return SessionExit(Result.retry(), SessionExitReason.FOREGROUND_REFUSED)
        }
        val session = GattSession(
            transport = AndroidGattTransport(applicationContext, device, adapter),
            decoder = BeurerDecoder(),
            consentStore = app.scaleProfileStore,
            deviceAddress = address,
            diagnostics = app.diagnosticsCounters,
            purpose = ScaleSessionPurpose.MEASUREMENT,
            log = { Log.i(TAG, it) },
        )
        val outcome = app.scaleOperationCoordinator
            .withScale(ScaleSessionPurpose.MEASUREMENT) { session.run() }
        // The one line that says what a weigh-in actually did. Until this
        // existed, a session that reached the radio and came back empty was
        // indistinguishable, from outside the process, from one that never ran.
        Log.i(TAG, "session ended: ${describe(outcome)}")
        return resultFor(app, address, outcome)
    }

    /**
     * `setForeground` is the one call in this worker that can fail for reasons
     * outside the app: on API 31+ a background start raises
     * `ForegroundServiceStartNotAllowedException`, and API 34 adds
     * `ForegroundServiceTypeException`/`SecurityException` around the
     * `connectedDevice` type. Those have no common supertype worth naming, and
     * left uncaught `CoroutineWorker` absorbs them itself — `doWork` never
     * reaches the session, so the weigh-in disappears with no retry and no
     * diagnostic. This is E10's mechanism arriving by its other route (a worker
     * downgraded out of the expedited quota is exactly a worker the platform
     * will not let go foreground), so it books the same `MISSED_QUOTA` counter.
     *
     * `applicationContext as BasculeApplication` above keeps this whole method
     * out of the JVM test lane (devil's-advocate review, testing gaps round 4)
     * — [classifyForegroundStartFailure] is split out so the one piece of
     * actual branching logic here is still directly testable.
     */
    private suspend fun enterForeground(app: BasculeApplication): Boolean =
        runNonCancelling(onError = { error ->
            if (error is Error) {
                Log.e(TAG, "severe error contained entering the foreground state", error)
            }
            app.diagnosticsCounters.increment(classifyForegroundStartFailure(error))
            false
        }) {
            setForeground(foregroundInfo())
            true
        }

    /**
     * Best-effort by construction: a cooldown that cannot be written is a
     * missed weigh-in later, never a crashed worker now. Off the worker's
     * dispatcher because `settle` commits synchronously to disk.
     */
    /**
     * Best-effort for the same reason [settleCooldown] is, and recorded on
     * every exit rather than only the interesting ones: an attempt that ended
     * before it reached the radio is precisely the case the user could not
     * otherwise distinguish from no attempt at all.
     */
    private suspend fun recordAttempt(reason: SessionExitReason) {
        val log = (applicationContext as? BasculeApplication)?.captureAttemptLog ?: return
        val outcome = captureOutcomeFor(reason)
        withContext(Dispatchers.IO) {
            runCatching {
                // Read before write: the transition is from what the user was
                // last told to what just happened.
                val previous = log.last.value?.outcome
                log.record(outcome)
                CaptureAttentionNotifier(applicationContext).apply(attentionTransition(previous, outcome))
            }.onFailure { Log.w(TAG, "could not record the capture attempt", it) }
        }
    }

    private suspend fun settleCooldown(address: String, disposition: CooldownDisposition) {
        withContext(Dispatchers.IO) {
            runCatching { ScanEnqueueCooldown(applicationContext).settle(address, disposition) }
                .onFailure { Log.w(TAG, "could not settle the scan cooldown", it) }
        }
    }

    private fun describe(outcome: SessionOutcome): String = when (outcome) {
        is SessionOutcome.Completed ->
            if (outcome.reading != null) "captured a reading" else "completed with no reading"
        is SessionOutcome.Missed -> "no reading (${outcome.reason})"
        is SessionOutcome.DecodeFailure -> "decode failure (${outcome.malformedCount} malformed frames)"
        SessionOutcome.Incompatible -> "device is not a compatible scale"
        is SessionOutcome.HandshakeFailed -> "handshake failed (${outcome.detail})"
        SessionOutcome.PairingRequired -> "pairing required (the Bluetooth pairing request was not accepted)"
    }

    private suspend fun resultFor(
        app: BasculeApplication,
        address: String,
        outcome: SessionOutcome,
    ): SessionExit = when (outcome) {
        // A null reading is a session that completed without a measurement —
        // the registration path. Unreachable from this worker, which always
        // runs with stopAfterHandshake = false, but stated rather than assumed.
        // The drain stays outside the null check: it flushes rows this session
        // did not produce, so it is owed on every completed session.
        is SessionOutcome.Completed -> {
            outcome.reading?.let { app.readingIngestor.ingest(address, it) }
            app.deliveryScheduler.triggerImmediateDrain()
            SessionExit(
                Result.success(),
                if (outcome.reading != null) {
                    SessionExitReason.CAPTURED
                } else {
                    SessionExitReason.COMPLETED_WITHOUT_READING
                },
            )
        }

        is SessionOutcome.Missed -> SessionExit(
            if (outcome.reason == MissReason.ADAPTER_OFF) Result.retry() else Result.success(),
            exitReasonFor(outcome.reason),
        )

        // Transient RF corruption during an otherwise healthy session, so it
        // gets the same treatment as ADAPTER_OFF above rather than sharing
        // Incompatible's terminal failure. Note the retry is best-effort: it
        // re-enters doWork(), which re-checks STALENESS_ABORT_MILLIS, and
        // WorkManager's backoff floor usually lands past that 20 s window — at
        // which point this returns success() without touching the radio. That
        // makes retry() the honest classification, not an effective recovery.
        // The cooldown backoff is what actually gives the next advertisement a
        // way back in.
        is SessionOutcome.DecodeFailure -> SessionExit(Result.retry(), SessionExitReason.DECODE_FAILURE)

        // A statement about the device, not about this attempt: retrying cannot
        // change it, and failure() is what feeds E4's incompatibleStreak story.
        SessionOutcome.Incompatible -> SessionExit(Result.failure(), SessionExitReason.INCOMPATIBLE)

        // The scale refused or never answered Register/Consent. Not retried
        // here: E6 already ran its own ack ladder inside the session, and a
        // refused registration needs the user to re-pair, not another attempt.
        is SessionOutcome.HandshakeFailed -> SessionExit(Result.failure(), SessionExitReason.HANDSHAKE_FAILED)

        // Needs the user, not a retry — but the system re-raises its pairing
        // request on the next session, so backing off (rather than holding)
        // keeps giving them chances at a widening interval.
        SessionOutcome.PairingRequired -> SessionExit(Result.failure(), SessionExitReason.PAIRING_REQUIRED)
    }

    /** Pairs a worker result with the reason, so [doWork] can settle on both. */
    private data class SessionExit(val result: Result, val reason: SessionExitReason)

    private fun foregroundInfo(): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                applicationContext.getString(R.string.scale_capture_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(applicationContext.getString(R.string.scale_capture_title))
            .setContentText(applicationContext.getString(R.string.scale_capture_text))
            .setOngoing(true).build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "scale-session"
        const val KEY_ADDRESS = "address"
        const val KEY_SEEN_AT = "seen_at"
        const val STALENESS_ABORT_MILLIS = 20_000L
        private const val CHANNEL = "scale_capture"
        private const val NOTIFICATION_ID = 720
        private const val TAG = "ScaleSessionWorker"
    }
}

/**
 * Which diagnostic counter a `setForeground` failure books. Kept as a pure,
 * `Context`-free function — the seam [enterForeground] otherwise has no
 * testable piece of, since `applicationContext as BasculeApplication` blocks
 * this class itself from the JVM test lane (devil's-advocate review, testing
 * gaps round 4).
 *
 * A single counter today because `ForegroundServiceStartNotAllowedException`,
 * `ForegroundServiceTypeException`, and `SecurityException` are all the same
 * story for this worker — the platform refused a foreground start right now,
 * not a statement about this device — but the branch point exists here,
 * rather than inline in [enterForeground], for whenever that stops being
 * true.
 */
internal fun classifyForegroundStartFailure(error: Throwable): DiagnosticsCounterKey = when (error) {
    // Every case maps here today (ForegroundServiceStartNotAllowedException,
    // ForegroundServiceTypeException, and SecurityException are all the same
    // story for this worker), but the `when` on `error` — rather than
    // ignoring the parameter — is the seam for when that stops being true.
    else -> DiagnosticsCounterKey.MISSED_QUOTA
}

/**
 * Every way [ScaleSessionWorker] can finish, named so the cooldown decision is
 * a pure function of it. Kept exhaustive rather than collapsed to a boolean:
 * the three cooldown treatments turn on *why* a session ended, and the early
 * exits — which never reach [SessionOutcome] at all — are the ones that were
 * silently poisoning the window.
 */
internal enum class SessionExitReason {
    STALE_ADVERTISEMENT,
    PERMISSION_DENIED,
    NO_ADAPTER,
    ADAPTER_DISABLED,
    NOT_ACTIVE_PROFILE,
    UNRESOLVABLE_DEVICE,
    FOREGROUND_REFUSED,
    CAPTURED,
    COMPLETED_WITHOUT_READING,

    /**
     * Listened and nobody stepped on — the budget ran out, or the scale ended
     * the link on its own idle timer. Not a failure either way.
     */
    IDLE,
    MISSED,
    DECODE_FAILURE,
    INCOMPATIBLE,
    HANDSHAKE_FAILED,

    /** The scale asked to pair and nobody accepted Android's request in time. */
    PAIRING_REQUIRED,

    /**
     * The session threw, or was cancelled before it could classify itself.
     * Present so that the `finally` in [ScaleSessionWorker.doWork] always has a
     * reason to settle with — an unclassified failure that skipped settling
     * would reinstate the five-minute lockout by the back door.
     */
    UNEXPECTED_ERROR,
}

/**
 * Which exit a miss is. Pure, for the same testability reason as the two
 * mappings below it.
 *
 * Two misses are the scale idling rather than the session failing, and must
 * not feed the escalating backoff: `NO_MEASUREMENT` is a listen that ran its
 * whole budget, and `DROPPED` is the scale ending the link on its own idle
 * timer — [GattSession.reconnectOnce] is the only producer of `DROPPED`, and it
 * is reachable only from `MEASURING`, so a `DROPPED` is always post-subscribe.
 * Every other miss happened before the session was in a position to hear a
 * weigh-in at all.
 */
internal fun exitReasonFor(reason: MissReason): SessionExitReason = when (reason) {
    MissReason.NO_MEASUREMENT, MissReason.DROPPED -> SessionExitReason.IDLE
    MissReason.CONNECT_TIMEOUT,
    MissReason.CONTENTION,
    MissReason.QUOTA,
    MissReason.BOND_FAILED,
    MissReason.ADAPTER_OFF,
    MissReason.GATT_ERROR,
    MissReason.DISCOVERY_FAILED,
    MissReason.GRACEFUL_DISCONNECT,
    -> SessionExitReason.MISSED
}

/**
 * Which cooldown treatment each exit earns. Pure and `Context`-free for the
 * same reason [classifyForegroundStartFailure] is: `applicationContext as
 * BasculeApplication` keeps the worker itself out of the JVM lane, so the
 * policy is split from the wiring to stay directly testable.
 *
 * [SessionExitReason.STALE_ADVERTISEMENT] is the only [CooldownDisposition.RELEASE]
 * on purpose. It is the one exit that never reaches the radio *and* is expected
 * to succeed on an immediate retry, because the next advertisement carries a
 * fresh `seenAt`. Every other non-capturing exit describes a condition that
 * will still hold a few milliseconds later, so releasing them would reconnect
 * on every advertisement in the burst — the defect `ScanEnqueueCooldown` was
 * written to prevent.
 */
internal fun cooldownDispositionFor(reason: SessionExitReason): CooldownDisposition = when (reason) {
    SessionExitReason.STALE_ADVERTISEMENT -> CooldownDisposition.RELEASE

    // Reconnect promptly and keep listening. The BF720 advertises whenever it
    // is awake and only ever indicates a *live* weigh-in to an already
    // consented client, so coverage — the fraction of time a session is
    // connected — is what decides whether stepping on works.
    SessionExitReason.IDLE -> CooldownDisposition.PAUSE

    // A reading is already stored; a second session would only re-capture it.
    // Incompatible is terminal for this device, so it earns the full window too.
    SessionExitReason.CAPTURED, SessionExitReason.INCOMPATIBLE -> CooldownDisposition.HOLD

    SessionExitReason.PERMISSION_DENIED,
    SessionExitReason.NO_ADAPTER,
    SessionExitReason.ADAPTER_DISABLED,
    SessionExitReason.NOT_ACTIVE_PROFILE,
    SessionExitReason.UNRESOLVABLE_DEVICE,
    SessionExitReason.FOREGROUND_REFUSED,
    SessionExitReason.COMPLETED_WITHOUT_READING,
    SessionExitReason.MISSED,
    SessionExitReason.DECODE_FAILURE,
    SessionExitReason.HANDSHAKE_FAILED,
    SessionExitReason.PAIRING_REQUIRED,
    SessionExitReason.UNEXPECTED_ERROR,
    -> CooldownDisposition.BACKOFF
}

/**
 * Collapses the exhaustive exit vocabulary into the five things a person
 * standing on a scale can actually act on. Pure, and separate from
 * [cooldownDispositionFor], because the two answer different questions: one is
 * "when may we try again", the other is "what do we tell the user". They
 * deliberately group the reasons differently — a refused foreground start earns
 * a retry backoff but reads to the user as the phone missing its window.
 */
internal fun captureOutcomeFor(reason: SessionExitReason): CaptureOutcome = when (reason) {
    SessionExitReason.CAPTURED -> CaptureOutcome.CAPTURED

    // The phone's scheduler, not the scale: the advertisement was live when it
    // was enqueued and the worker did not get a slot in time.
    SessionExitReason.STALE_ADVERTISEMENT,
    SessionExitReason.FOREGROUND_REFUSED,
    -> CaptureOutcome.MISSED_THE_WINDOW

    // Something the user can fix: link a scale, grant the permission, turn
    // Bluetooth on.
    SessionExitReason.PERMISSION_DENIED,
    SessionExitReason.NO_ADAPTER,
    SessionExitReason.ADAPTER_DISABLED,
    SessionExitReason.NOT_ACTIVE_PROFILE,
    SessionExitReason.UNRESOLVABLE_DEVICE,
    -> CaptureOutcome.NOT_READY

    SessionExitReason.INCOMPATIBLE -> CaptureOutcome.INCOMPATIBLE

    SessionExitReason.IDLE -> CaptureOutcome.IDLE

    // Reached the scale, came back empty.
    SessionExitReason.COMPLETED_WITHOUT_READING,
    SessionExitReason.MISSED,
    SessionExitReason.DECODE_FAILURE,
    SessionExitReason.HANDSHAKE_FAILED,
    -> CaptureOutcome.NO_READING

    SessionExitReason.UNEXPECTED_ERROR -> CaptureOutcome.FAILED

    SessionExitReason.PAIRING_REQUIRED -> CaptureOutcome.NEEDS_PAIRING
}
