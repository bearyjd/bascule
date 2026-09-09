package com.ventouxlabs.bascule.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.runNonCancelling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-arms the scan when the Bluetooth adapter comes back on, for the same
 * reason [BootReceiver] does it after a reboot: **a scan registration belongs
 * to the running Bluetooth stack instance and does not survive that stack going
 * away** (00-design.md §8.2 says this about reboot; an adapter cycle has
 * exactly the same effect and was not covered).
 *
 * Observed on hardware 2026-09-08: the stack restarted at 18:49:14 — every
 * `GattServer` re-registered and bonded-device storage reloaded — and Bascule
 * never re-registered, while other apps on the device did. Capture had been
 * dead for eleven hours. Nothing looked wrong from any angle the app or a
 * `dumpsys` could show: [BridgeForegroundService] was still running, both
 * config toggles were on, the enqueue cooldown was empty. Only the
 * registration inside the stack was gone, and no code path noticed.
 *
 * Why this is not covered by the adapter handling already in
 * `AndroidGattTransport`: that receiver lives for the duration of one
 * [com.ventouxlabs.bascule.ble.session.GattSession] and exists to abort a
 * session in flight. When the adapter cycles while *no* session is running —
 * the overwhelmingly common case, since sessions are short and the gaps
 * between them are not — there is nothing listening at all.
 *
 * The bridge's scan is re-registered *in place*, via
 * [BridgeForegroundService.EXTRA_REARM_SCAN], rather than by stopping and
 * restarting the service. An external stop/start looks equivalent and is not:
 * `Context.stopService` is asynchronous, so the `start()` can be delivered to
 * the still-live instance, whose `onStartCommand` never called
 * `startActiveScan()` — leaving the invalidated registration in place and the
 * service running with no scan, which is the very state this file exists to
 * end. Found by an independent review of the first version of this fix, which
 * had exactly that race.
 *
 * [rearm] defaults to the real path via [BasculeApplication] — Android
 * instantiates receivers reflectively, so production behavior is unchanged,
 * and a test constructs this directly with a fake instead. See
 * `ScanBroadcastReceiver`'s KDoc for why reaching a real [BasculeApplication]
 * is not an option in this project's test lane.
 */
class AdapterStateReceiver(
    private val rearm: suspend (Context) -> Unit = { context ->
        val app = context.applicationContext as BasculeApplication
        // Unconditional, and NOT gated on the always-on setting: the service
        // may also be running a bounded "Weigh now" window with both toggles
        // off, and that window's scan is invalidated by the adapter cycle
        // exactly like the always-on one. rearmScan() is itself a no-op when
        // nothing is running, so it cannot start a bridge the user turned off
        // — which is why the config read that used to gate this is gone
        // rather than merely widened.
        app.bridgeServiceController.rearmScan()
        // Independent path, independently invalidated: this is the low-power
        // PendingIntent scan, and it is its own arm() gate for automatic
        // capture. Nothing above depends on its result.
        app.scaleScanner.arm()
    },
    private val onFailure: (Context, Throwable) -> Unit = { context, error ->
        (context.applicationContext as BasculeApplication).recordBootArmFailure(error)
    },
    /** Injectable only so the timeout path is testable without an 8-second wait. */
    private val armTimeoutMillis: Long = ARM_TIMEOUT_MILLIS,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!isAdapterTurnedOn(intent)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runNonCancelling(onError = { error ->
                    if (error is Error) {
                        Log.e(TAG, "severe error contained while re-arming after adapter on", error)
                    }
                    onFailure(context, error)
                }) {
                    withTimeoutOrNull(armTimeoutMillis) { rearm(context) }
                }
            } finally {
                // Nullable in a unit-test lane: goAsync() only returns a
                // PendingResult when the framework put one there.
                pending?.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AdapterStateReceiver"
        const val ARM_TIMEOUT_MILLIS = 8_000L
    }
}

/**
 * True only for the adapter reaching a fully-on state. `STATE_TURNING_ON` is
 * deliberately excluded: registering a scan before the stack finishes coming
 * up is what produces a registration that silently never delivers — the exact
 * failure this file exists to fix, reintroduced one step earlier.
 */
internal fun isAdapterTurnedOn(intent: Intent): Boolean =
    intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
        intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
