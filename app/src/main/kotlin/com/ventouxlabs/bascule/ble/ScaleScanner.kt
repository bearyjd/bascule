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
        // Inside the guard, not before it: setDeviceAddress throws
        // IllegalArgumentException on a malformed address, and a stored profile
        // can carry one. arm() runs inside BasculeApplication's launch, where an
        // escaping throwable kills the process on every launch.
        return runCatching {
            val filter = ScanFilter.Builder().setDeviceAddress(profile.deviceAddress)
                .setServiceUuid(ParcelUuid(SigWeightProfile.WEIGHT_SCALE_SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            // Re-arming reuses one PendingIntent identity with a different filter,
            // which the platform may either ignore (leaving the previous profile's
            // address filtered, so capture silently stops) or reject with
            // SCAN_FAILED_ALREADY_STARTED. Dropping the old registration first is
            // correct under either behavior.
            disarm()
            scanner?.startScan(listOf(filter), settings, pendingIntent) == 0
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun disarm() { runCatching { scanner?.stopScan(pendingIntent) } }

    companion object {
        const val ACTION_SCAN = "com.ventouxlabs.bascule.SCALE_SCAN"
        private const val REQUEST_CODE = 720
    }
}
