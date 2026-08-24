@file:Suppress("MaxLineLength")

package com.ventouxlabs.bascule.ble

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.ScaleProfileStore
import kotlinx.coroutines.flow.first

class ScaleScanner(context: Context, private val config: ConfigStore, private val profiles: ScaleProfileStore) {
    private val appContext = context.applicationContext
    private val scanner get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    private val pendingIntent get() = PendingIntent.getBroadcast(
        appContext, REQUEST_CODE,
        Intent(appContext, ScanBroadcastReceiver::class.java).setAction(ACTION_SCAN),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission")
    suspend fun arm(): Boolean {
        if (!config.automaticCaptureEnabled.first()) return false
        val profile = profiles.activeProfile.value ?: return false
        val filter = ScanFilter.Builder().setDeviceAddress(profile.deviceAddress)
            .setServiceUuid(ParcelUuid(SigWeightProfile.WEIGHT_SCALE_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        return runCatching { scanner?.startScan(listOf(filter), settings, pendingIntent) == 0 }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun disarm() { runCatching { scanner?.stopScan(pendingIntent) } }

    companion object { const val ACTION_SCAN = "com.ventouxlabs.bascule.SCALE_SCAN"; private const val REQUEST_CODE = 720 }
}
