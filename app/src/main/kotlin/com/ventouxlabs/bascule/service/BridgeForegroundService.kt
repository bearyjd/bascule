@file:Suppress("MagicNumber", "MaxLineLength")

package com.ventouxlabs.bascule.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.session.WorkManagerScaleSessionEnqueuer

/** Optional active-scan fallback; every result is routed through the same unique worker path. */
class BridgeForegroundService : Service() {
    private val scanner get() = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    private val enqueuer by lazy { WorkManagerScaleSessionEnqueuer(this) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Scale bridge active").setContentText("Waiting for the configured scale")
                .setOngoing(true).build(),
        )
        startActiveScan()
    }

    @SuppressLint("MissingPermission")
    private fun startActiveScan() {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) return
        val active = (application as BasculeApplication).scaleProfileStore.activeProfile.value ?: return
        val filter = ScanFilter.Builder().setDeviceAddress(active.deviceAddress)
            .setServiceUuid(ParcelUuid(SigWeightProfile.WEIGHT_SCALE_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner?.startScan(listOf(filter), settings, callback) }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            enqueuer.enqueue(result.device.address, System.currentTimeMillis())
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        runCatching { scanner?.stopScan(callback) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Scale bridge", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object { private const val CHANNEL = "scale_bridge"; private const val NOTIFICATION_ID = 721 }
}
