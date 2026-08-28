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
    private val scanLock = Any()
    private val scanner get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    private val pendingIntent get() = PendingIntent.getBroadcast(
        appContext, REQUEST_CODE,
        Intent(appContext, ScanBroadcastReceiver::class.java).setAction(ACTION_SCAN),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /**
     * The suspending reads stay outside [scanLock]: only the stop-then-start
     * pair below has to be atomic. Four callers can reach [arm]/[disarm]
     * concurrently — app startup, [com.ventouxlabs.bascule.service.BootReceiver],
     * and both view models — and interleaving them drops a just-registered scan,
     * since the stop half targets the same PendingIntent identity the other
     * call had just registered.
     */
    suspend fun arm(): Boolean {
        if (!config.automaticCaptureEnabled.first()) return false
        val profile = profiles.activeProfile.value ?: return false
        return synchronized(scanLock) { register(profile.deviceAddress) }
    }

    fun disarm() = synchronized(scanLock) { stopScan() }

    @SuppressLint("MissingPermission")
    private fun register(deviceAddress: String): Boolean =
        // Inside the guard, not before it: setDeviceAddress throws
        // IllegalArgumentException on a malformed address, and a stored profile
        // can carry one. arm() runs inside BasculeApplication's launch, where an
        // escaping throwable kills the process on every launch.
        runCatching {
            val filter = ScanFilter.Builder().setDeviceAddress(deviceAddress)
                .setServiceUuid(ParcelUuid(SigWeightProfile.WEIGHT_SCALE_SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            // Re-arming reuses one PendingIntent identity with a different filter,
            // which the platform may either ignore (leaving the previous profile's
            // address filtered, so capture silently stops) or reject with
            // SCAN_FAILED_ALREADY_STARTED. Dropping the old registration first is
            // correct under either behavior. Calls stopScan() rather than disarm()
            // so the lock is taken exactly once, not re-entered.
            stopScan()
            scanner?.startScan(listOf(filter), settings, pendingIntent) == 0
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun stopScan() { runCatching { scanner?.stopScan(pendingIntent) } }

    companion object {
        const val ACTION_SCAN = "com.ventouxlabs.bascule.SCALE_SCAN"
        private const val REQUEST_CODE = 720
    }
}
