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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.R
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.session.ScaleSessionEnqueuer
import com.ventouxlabs.bascule.ble.session.WorkManagerScaleSessionEnqueuer

/**
 * Optional active-scan fallback; every result is routed through the same unique
 * worker path.
 *
 * Lifecycle is owner-driven: callers acquire a [BridgeOwner] and release it,
 * and [releaseOwner] is the **only** place that decides a running service
 * should stop. See `docs/prp/06-owner-aware-bridge-lifecycle.md` for the three
 * bugs that shape replaced, and [BridgeOwner] for why naming the callers is
 * what fixes them.
 */
class BridgeForegroundService : Service(), BridgeOwnership {
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

    /** Injectable for the same reason as [boundStopScheduler]: a JVM test has no real clock to wait out. */
    internal var elapsedClock: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * Who wants this service running. Replaces `lastStartMode`, an `isRunning`
     * flag and a `stopRequested` flag in the controller, all three of which
     * existed to reconstruct this one fact from its side effects.
     *
     * Assigned a new set rather than mutated, under [synchronized] because a
     * release can arrive from a caller's thread while a start is being handled
     * on the main one.
     */
    @Volatile
    private var heldOwners: Set<BridgeOwner> = emptySet()

    /**
     * The newest `startId` seen. [stopSelf] takes it rather than the id of
     * whichever start armed a timer: `stopSelf(startId)` is a documented no-op
     * once a *newer* start has landed, so an expiring "Weigh now" that passed
     * its own stale id would silently fail to stop a service nobody owns.
     */
    private var latestStartId: Int = 0

    /**
     * When the running "Weigh now" window ends, or null when none is running.
     * Carried so a re-arm — which allocates a newer `startId` and thereby
     * orphans the original timer — can rebind the stop for whatever time is
     * left instead of leaving a `SCAN_MODE_BALANCED` scan running forever.
     */
    private var weighNowDeadline: Long? = null

    override val owners: Set<BridgeOwner> get() = heldOwners

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
        instance = this
        startActiveScan()
    }

    /**
     * `onCreate` runs once; this runs on every `startService`/
     * `startForegroundService` call, including a later one against an
     * already-running instance — which is exactly when `weighNow()`'s bounded
     * call needs to land, since `Always-on foreground fallback` skips it
     * entirely when this service is already running unbounded (see
     * `ScaleViewModel.weighNow`).
     *
     * Every branch is an *acquire*; nothing here stops the service. That is
     * what removed this file's recurring bug: a start can no longer invalidate
     * another caller's pending stop, because only [releaseOwner] stops
     * anything, and only when the last owner has let go.
     *
     * The restart mode follows from the owner set rather than being remembered
     * across calls ([restartMode]), so a re-arm cannot flip an always-on
     * service to `START_NOT_STICKY` or a bounded one to `START_STICKY` — the
     * latter being the restart-unbounded failure this KDoc used to guard by
     * replaying a stored `lastStartMode`.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        // A sticky restart delivers a null Intent. START_STICKY is only ever
        // returned while ALWAYS_ON is held, so a null Intent *is* the always-on
        // owner asking for its scan back — no config read needed to know that.
        // WEIGH_NOW is deliberately not restored: the window is seconds long
        // and the user was standing on the scale, so a silently resurrected one
        // would hold a BALANCED scan with nobody watching. They can tap again.
        if (intent == null) {
            acquire(BridgeOwner.ALWAYS_ON)
            return restartMode()
        }
        // A re-arm is not a new start: it is this same instance reattaching its
        // scan to a Bluetooth stack that replaced the one the old registration
        // belonged to. It changes no ownership at all — it only has to move the
        // "Weigh now" stop onto the start id this call just allocated.
        if (intent.getBooleanExtra(EXTRA_REARM_SCAN, false)) {
            restartActiveScan()
            rebindWeighNowStop()
            return restartMode()
        }
        val boundMillis = intent.getLongExtra(EXTRA_BOUND_MILLIS, 0L)
        if (boundMillis <= 0) {
            acquire(BridgeOwner.ALWAYS_ON)
            return restartMode()
        }
        acquire(BridgeOwner.WEIGH_NOW)
        weighNowDeadline = elapsedClock() + boundMillis
        // A bounded start is "Weigh now": the user is standing on the scale
        // right now, so any backoff earned by earlier failures has to go. It is
        // the one control they have, and a throttle that can block it is worse
        // than no throttle at all.
        activeAddressProvider()?.let(cooldown::clear)
        boundStopScheduler(boundMillis) { releaseOwner(BridgeOwner.WEIGH_NOW) }
        return restartMode()
    }

    /**
     * Acquiring is idempotent because a [Set] makes it so — `onStartCommand`
     * can be redelivered, and a second acquire of an owner already held must
     * not change anything.
     */
    private fun acquire(owner: BridgeOwner) = synchronized(this) {
        heldOwners = heldOwners + owner
    }

    /**
     * The one place that stops a running service.
     *
     * Releasing a claim nobody holds is a no-op rather than a stop: the
     * always-on toggle can be switched off twice, and a "Weigh now" window can
     * be cancelled after it already expired. Stopping on those would `stopSelf`
     * a service a concurrent acquire had legitimately just started — the same
     * race this refactor removed, in new clothes. The condition is "the set
     * *became* empty", never "the set is empty".
     */
    override fun releaseOwner(owner: BridgeOwner) {
        val becameEmpty = synchronized(this) {
            if (owner !in heldOwners) return
            heldOwners = heldOwners - owner
            if (owner == BridgeOwner.WEIGH_NOW) weighNowDeadline = null
            heldOwners.isEmpty()
        }
        if (becameEmpty) stopSelf(latestStartId)
    }

    /**
     * `START_STICKY` only while the always-on owner holds the bridge: a sticky
     * restart delivers a null `Intent`, and restarting a bounded window as an
     * unbounded scan nothing will ever stop is worse than losing the window.
     */
    private fun restartMode(): Int =
        if (BridgeOwner.ALWAYS_ON in heldOwners) START_STICKY else START_NOT_STICKY

    /**
     * Replaces the scan registration in place. Stopping the old callback first
     * is best-effort by design: after an adapter cycle the registration it
     * refers to is already gone, so the call is expected to be a no-op — but
     * skipping it would leak a live registration in the case this is ever
     * called without one (a future caller, a stack that survived).
     */
    /**
     * Re-arms the "Weigh now" window's release, because the timer armed by the
     * original bounded start would `stopSelf` a start id this re-arm has just
     * superseded. Without it a Bluetooth cycle during a "Weigh now" leaves a
     * `SCAN_MODE_BALANCED` foreground scan running forever — worse for both
     * batteries than the adapter-cycle gap the re-arm exists to close.
     *
     * Note what this schedules: a *release*, not a stop. If the always-on owner
     * acquired the bridge during the window, the release fires, the window
     * genuinely ends, and the service keeps running because someone still
     * wants it. That case needed a special "clear the deadline" branch before
     * owners existed.
     *
     * A window that already elapsed releases at once rather than scheduling
     * zero: the re-arm may be the first thing to run after a long stall.
     */
    private fun rebindWeighNowStop() {
        val end = weighNowDeadline ?: return
        val remaining = end - elapsedClock()
        if (remaining <= 0) {
            releaseOwner(BridgeOwner.WEIGH_NOW)
            return
        }
        boundStopScheduler(remaining) { releaseOwner(BridgeOwner.WEIGH_NOW) }
    }

    @SuppressLint("MissingPermission")
    private fun restartActiveScan() {
        runCatching { scanner?.stopScan(callback) }
        startActiveScan()
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
        // Only if this is still the live instance: a replacement created before
        // this one finished tearing down must not be unpublished by it.
        if (instance === this) instance = null
        heldOwners = emptySet()
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

        /**
         * Set by [AdapterStateReceiver] to re-register the scan against a
         * replacement Bluetooth stack, without the stop/start race an external
         * restart has: `Context.stopService` is asynchronous, so a `start()`
         * issued straight after it can be delivered to the still-live instance,
         * whose [onStartCommand] never called [startActiveScan] — leaving the
         * invalidated registration in place and the service running with no
         * scan at all.
         */
        const val EXTRA_REARM_SCAN = "rearm_scan"

        /**
         * The live instance's ownership, or null when no bridge is up.
         *
         * There is one service and one process, so a release can be a direct
         * call instead of an intent — which matters because a release must work
         * from the background, and `startForegroundService` throws there on
         * API 31+. Acquires still go by intent, since they may need to *create*
         * the service.
         *
         * Replaces an `isRunning` boolean and the controller's `stopRequested`
         * flag. Both existed because `Context.stopService` is asynchronous, so
         * observed liveness and intent could disagree; a synchronous release
         * against [owners] leaves no gap for them to disagree in.
         *
         * Published after [onCreate]'s permission gate rather than at its top,
         * so a start that immediately stops itself never reads as running.
         */
        @Volatile
        internal var instance: BridgeOwnership? = null
            private set
    }
}
