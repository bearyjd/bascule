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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.R
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.session.ScaleSessionEnqueuer
import com.ventouxlabs.bascule.ble.session.WorkManagerScaleSessionEnqueuer

/** Optional active-scan fallback; every result is routed through the same unique worker path. */
class BridgeForegroundService : Service() {
    private val scanner get() = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    /**
     * The same seam, for the same reason, as
     * [com.ventouxlabs.bascule.ble.ScanBroadcastReceiver.enqueuerFactory] — the
     * other of the two real dispatch paths. A [Service] is instantiated by the
     * framework through a no-arg constructor, so this is a reassignable
     * property a test overwrites on the built instance rather than a
     * constructor parameter. Production behavior is unchanged by the default.
     */
    internal var enqueuerFactory: (Context) -> ScaleSessionEnqueuer =
        { WorkManagerScaleSessionEnqueuer(it) }

    private val enqueuer by lazy { enqueuerFactory(this) }
    private val cooldown = ScanEnqueueCooldown(ENQUEUE_COOLDOWN_MILLIS)

    /**
     * The permission check runs before [startForeground], not after it as the
     * scan did: on API 34+ a `connectedDevice` foreground service may only start
     * while the app actually holds a Bluetooth runtime permission, so checking
     * afterwards means the `SecurityException` has already been thrown. Same
     * order `ScaleSessionWorker.doWork` uses. Stopping here rather than idling
     * also discharges the `startForegroundService` contract — a service brought
     * down before `startForeground` has its foreground timeout cancelled.
     */
    override fun onCreate() {
        super.onCreate()
        if (!hasScanPermission()) {
            stopSelf()
            return
        }
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(getString(R.string.scale_bridge_title))
                .setContentText(getString(R.string.scale_bridge_text))
                .setOngoing(true).build(),
        )
        startActiveScan()
    }

    /** BLUETOOTH_SCAN, not CONNECT: this service only ever scans. */
    private fun hasScanPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startActiveScan() {
        val active = (application as BasculeApplication).scaleProfileStore.activeProfile.value ?: return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        // Inside the guard, not before it — see ScaleScanner.arm() for why.
        runCatching {
            val filter = ScanFilter.Builder().setDeviceAddress(active.deviceAddress)
                .setServiceUuid(ParcelUuid(SigWeightProfile.WEIGHT_SCALE_SERVICE)).build()
            val settings = ScanSettings.Builder()
                // BALANCED, not LOW_LATENCY: this scan runs from the moment the
                // service starts until it is stopped, and ScaleSessionWorker's
                // 20s staleness budget absorbs the extra detection latency.
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                // Batching collapses a burst of advertisements into one delivery.
                // startScan rejects a non-zero delay outright when the controller
                // cannot offload batching, so ask the adapter first.
                .setReportDelay(if (adapter.isOffloadedScanBatchingSupported) BATCH_REPORT_DELAY_MILLIS else 0L)
                .build()
            adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, callback)
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = enqueueOnce(result)

        override fun onBatchScanResults(results: List<ScanResult>) = results.forEach(::enqueueOnce)
    }

    /** Internal so a test can drive it without going through `onCreate`'s scan start. */
    internal fun enqueueOnce(result: ScanResult) {
        val address = result.device?.address ?: return
        if (!cooldown.claim(address)) return
        enqueuer.enqueue(address, System.currentTimeMillis())
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        runCatching { scanner?.stopScan(callback) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.scale_bridge_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL = "scale_bridge"
        private const val NOTIFICATION_ID = 721
        private const val BATCH_REPORT_DELAY_MILLIS = 5_000L
        private const val ENQUEUE_COOLDOWN_MILLIS = 5L * 60 * 1_000
    }
}

/**
 * Gates repeat session enqueues for one device address. Without it every
 * advertisement — 2-10 per second while the scale is in radio range — starts a
 * fresh GATT connect/handshake cycle the moment the previous one finishes,
 * because `ExistingWorkPolicy` only suppresses work that is actually in flight.
 *
 * The window is stamped when a session is *enqueued* rather than when it ends:
 * the terminal outcome is known only inside `ScaleSessionWorker`. It is sized
 * well past `SessionBudget`'s 90s hard ceiling so that even a session that runs
 * to that ceiling still leaves several minutes of quiet behind it.
 */
internal class ScanEnqueueCooldown(
    private val windowMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastEnqueuedAt = mutableMapOf<String, Long>()

    /** Reserves the next session for [address], or returns false while the window is open. */
    @Synchronized
    fun claim(address: String): Boolean {
        val now = clock()
        val last = lastEnqueuedAt[address]
        if (last != null && now - last < windowMillis) return false
        lastEnqueuedAt[address] = now
        return true
    }
}
