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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
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

    /** The same seam as [enqueuerFactory], for [startActiveScan]'s early exits. */
    internal var activeAddressProvider: () -> String? =
        { (application as BasculeApplication).scaleProfileStore.activeProfile.value?.deviceAddress }

    /**
     * The same seam, for the same reason, as [enqueuerFactory] and
     * [activeAddressProvider]: a JVM test cannot wait out a real
     * [EXTRA_BOUND_MILLIS] window, so [onStartCommand] schedules through this
     * instead of a bare `Handler.postDelayed` call.
     */
    internal var boundStopScheduler: (millis: Long, onExpire: () -> Unit) -> Unit =
        { millis, onExpire -> Handler(Looper.getMainLooper()).postDelayed(onExpire, millis) }

    private val enqueuer by lazy { enqueuerFactory(this) }
    private val cooldown by lazy { ScanEnqueueCooldown(this) }

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

    /**
     * `onCreate` runs once; this runs on every `startService`/
     * `startForegroundService` call, including a later one against an
     * already-running instance — which is exactly when `weighNow()`'s bounded
     * call needs to land, since `Always-on foreground fallback` skips it
     * entirely when this service is already running unbounded (see
     * `ScaleViewModel.weighNow`). `START_STICKY`, unchanged from the platform
     * default this class relied on before overriding this method — a
     * `weighNow()` window is short enough that a mid-window process kill and
     * restart losing its bound timer is an acceptable, rare cost, and changing
     * the always-on toggle's restart behavior is not what this change is for.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val boundMillis = intent?.getLongExtra(EXTRA_BOUND_MILLIS, 0L) ?: 0L
        if (boundMillis > 0) boundStopScheduler(boundMillis) { stopSelf() }
        return START_STICKY
    }

    /** BLUETOOTH_SCAN, not CONNECT: this service only ever scans. */
    private fun hasScanPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Every exit that leaves no scan running stops the service, for the same
     * reason the permission gate in [onCreate] does: this service's whole
     * purpose is to hold a scan open, so one that has none is an ongoing
     * notification the user cannot dismiss over a process doing nothing. A
     * revoked permission reaching the [runCatching] as a `SecurityException`
     * (E13) is the same inert state arrived at by a different route, so it
     * gets the same treatment plus a diagnostic.
     */
    @SuppressLint("MissingPermission")
    private fun startActiveScan() {
        val activeAddress = activeAddressProvider()
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (activeAddress == null || adapter == null) {
            stopSelf()
            return
        }
        // Inside the guard, not before it — see ScaleScanner.arm() for why.
        runCatching {
            val filter = ScanFilter.Builder().setDeviceAddress(activeAddress)
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
                ?: error("no BluetoothLeScanner")
        }.onFailure { error ->
            Log.w(TAG, "active scan could not be started; stopping the bridge", error)
            stopSelf()
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
        private const val TAG = "BridgeForegroundService"
        private const val CHANNEL = "scale_bridge"
        private const val NOTIFICATION_ID = 721
        private const val BATCH_REPORT_DELAY_MILLIS = 5_000L

        /** Positive only on a `weighNow()`-bounded start; absent on the always-on toggle's unbounded one. */
        const val EXTRA_BOUND_MILLIS = "bound_millis"
    }
}
