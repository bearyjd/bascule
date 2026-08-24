@file:Suppress("CyclomaticComplexMethod", "ReturnCount", "MaxLineLength", "MagicNumber")

package com.ventouxlabs.bascule.ble.session

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder

class ScaleSessionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val address = inputData.getString(KEY_ADDRESS) ?: return Result.failure()
        val seenAt = inputData.getLong(KEY_SEEN_AT, 0L)
        if (seenAt <= 0L || System.currentTimeMillis() - seenAt > STALENESS_ABORT_MILLIS) return Result.success()
        if (android.os.Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.BLUETOOTH_CONNECT,
            ) != PackageManager.PERMISSION_GRANTED
        ) return Result.failure()
        setForeground(foregroundInfo())
        val app = applicationContext as BasculeApplication
        val adapter = applicationContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return Result.failure()
        if (!adapter.isEnabled) return Result.retry()
        val profile = app.scaleProfileStore.activeProfile.value
        if (profile == null || !profile.deviceAddress.equals(address, true)) return Result.success()
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return Result.failure()
        val session = GattSession(
            AndroidGattTransport(applicationContext, device, adapter), BeurerDecoder(), app.scaleProfileStore,
            address, app.diagnosticsCounters, purpose = ScaleSessionPurpose.MEASUREMENT,
        )
        return when (val outcome = app.scaleOperationCoordinator.withScale(ScaleSessionPurpose.MEASUREMENT) { session.run() }) {
            is SessionOutcome.Completed -> {
                outcome.readings.forEach { app.readingIngestor.ingest(address, it) }
                app.deliveryScheduler.triggerImmediateDrain()
                Result.success()
            }
            is SessionOutcome.Missed -> if (outcome.reason == MissReason.ADAPTER_OFF) Result.retry() else Result.success()
            else -> Result.failure()
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Scale capture", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("Connecting to scale")
            .setContentText("Capturing a measurement").setOngoing(true).build()
        return ForegroundInfo(
            NOTIFICATION_ID, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "scale-session"; const val KEY_ADDRESS = "address"; const val KEY_SEEN_AT = "seen_at"
        const val STALENESS_ABORT_MILLIS = 20_000L
        private const val CHANNEL = "scale_capture"; private const val NOTIFICATION_ID = 720
    }
}
