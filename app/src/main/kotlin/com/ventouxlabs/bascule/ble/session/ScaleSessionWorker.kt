package com.ventouxlabs.bascule.ble.session

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.R
import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder

class ScaleSessionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val address = inputData.getString(KEY_ADDRESS) ?: return Result.failure()
        val seenAt = inputData.getLong(KEY_SEEN_AT, 0L)
        if (seenAt <= 0L || System.currentTimeMillis() - seenAt > STALENESS_ABORT_MILLIS) return Result.success()
        if (!hasConnectPermission()) return Result.failure()
        setForeground(foregroundInfo())
        return runSession(address)
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    private suspend fun runSession(address: String): Result {
        val app = applicationContext as BasculeApplication
        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return Result.failure()
        if (!adapter.isEnabled) return Result.retry()
        val profile = app.scaleProfileStore.activeProfile.value
        if (profile == null || !profile.deviceAddress.equals(address, true)) return Result.success()
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return Result.failure()
        val session = GattSession(
            transport = AndroidGattTransport(applicationContext, device, adapter),
            decoder = BeurerDecoder(),
            consentStore = app.scaleProfileStore,
            deviceAddress = address,
            diagnostics = app.diagnosticsCounters,
            purpose = ScaleSessionPurpose.MEASUREMENT,
        )
        val outcome = app.scaleOperationCoordinator
            .withScale(ScaleSessionPurpose.MEASUREMENT) { session.run() }
        return resultFor(app, address, outcome)
    }

    private suspend fun resultFor(
        app: BasculeApplication,
        address: String,
        outcome: SessionOutcome,
    ): Result = when (outcome) {
        // A null reading is a session that completed without a measurement —
        // the registration path. Unreachable from this worker, which always
        // runs with stopAfterHandshake = false, but stated rather than assumed.
        // The drain stays outside the null check: it flushes rows this session
        // did not produce, so it is owed on every completed session.
        is SessionOutcome.Completed -> {
            outcome.reading?.let { app.readingIngestor.ingest(address, it) }
            app.deliveryScheduler.triggerImmediateDrain()
            Result.success()
        }
        is SessionOutcome.Missed ->
            if (outcome.reason == MissReason.ADAPTER_OFF) Result.retry() else Result.success()

        // Transient RF corruption during an otherwise healthy session, so it
        // gets the same treatment as ADAPTER_OFF above rather than sharing
        // Incompatible's terminal failure. Note the retry is best-effort: it
        // re-enters doWork(), which re-checks STALENESS_ABORT_MILLIS, and
        // WorkManager's backoff floor usually lands past that 20 s window — at
        // which point this returns success() without touching the radio. That
        // makes retry() the honest classification, not an effective recovery.
        is SessionOutcome.DecodeFailure -> Result.retry()

        // A statement about the device, not about this attempt: retrying cannot
        // change it, and failure() is what feeds E4's incompatibleStreak story.
        SessionOutcome.Incompatible -> Result.failure()

        // The scale refused or never answered Register/Consent. Not retried
        // here: E6 already ran its own ack ladder inside the session, and a
        // refused registration needs the user to re-pair, not another attempt.
        is SessionOutcome.HandshakeFailed -> Result.failure()
    }

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
    }
}
