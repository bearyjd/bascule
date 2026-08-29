package com.ventouxlabs.bascule.ble

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.ble.session.ScaleSessionEnqueuer
import com.ventouxlabs.bascule.ble.session.WorkManagerScaleSessionEnqueuer
import com.ventouxlabs.bascule.runNonCancelling
import com.ventouxlabs.bascule.service.ScanEnqueueCooldown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [enqueuerFactory], [activeAddressProvider] and [claimCooldown] default to the
 * real [WorkManagerScaleSessionEnqueuer], the app's active profile and the
 * shared [ScanEnqueueCooldown] — Android instantiates this receiver via a
 * no-arg reflective constructor, so production behavior is unchanged. A test
 * constructs it directly with fakes instead.
 *
 * [claimCooldown] is a `(Context, String) -> Boolean` rather than the cooldown
 * object itself only so this class can stay public while `ScanEnqueueCooldown`
 * stays `internal`.
 */
class ScanBroadcastReceiver(
    private val enqueuerFactory: (Context) -> ScaleSessionEnqueuer = { WorkManagerScaleSessionEnqueuer(it) },
    private val activeAddressProvider: (Context) -> String? = ::activeProfileAddress,
    private val claimCooldown: (Context, String) -> Boolean = ::claimSharedCooldown,
    /** Injectable only so the timeout path is testable without a 5-second wait. */
    private val enqueueTimeoutMillis: Long = ENQUEUE_TIMEOUT_MILLIS,
) : BroadcastReceiver() {
    /**
     * The enqueue is asynchronous — [ScaleSessionEnqueuer] resolves an
     * `ExistingWorkPolicy` against WorkManager's database before writing —
     * and this process may exist only to service this one broadcast. Without
     * `goAsync()` the OS is free to kill it at receiver priority the moment
     * `onReceive` returns, before the write lands, and the weigh-in is dropped
     * with nothing anywhere to say so. The window is bounded so a query that
     * never completes cannot hold the process to the broadcast ANR limit.
     *
     * Everything past the intent parse runs off the main thread: resolving the
     * active address constructs the encrypted profile store on first touch, and
     * claiming the cooldown writes to disk.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScaleScanner.ACTION_SCAN) return
        val addresses = scanResults(intent).mapNotNull { it.device?.address }
        if (addresses.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runNonCancelling(onError = { error ->
                    if (error is Error) {
                        Log.e(TAG, "severe error contained while dispatching a scan wake", error)
                    }
                    // A keystore fault opening the profile store must not reach the
                    // default uncaught handler — this runs on every advertisement.
                    Log.w(TAG, "scan wake could not be dispatched", error)
                }) {
                    dispatch(context, addresses)
                }
            } finally {
                // Nullable in a unit-test lane: goAsync() only returns a
                // PendingResult when the framework put one there.
                pending?.finish()
            }
        }
    }

    private suspend fun dispatch(context: Context, addresses: List<String>) {
        val address = targetAddress(context, addresses) ?: return
        if (!claimCooldown(context, address)) return
        val enqueued = CompletableDeferred<Unit>()
        enqueuerFactory(context).enqueue(address, System.currentTimeMillis()) { enqueued.complete(Unit) }
        withTimeoutOrNull(enqueueTimeoutMillis) { enqueued.await() }
    }

    /**
     * A batched delivery routinely carries several results, and the scale's own
     * is not necessarily first. The unknown-active fallback to the leading
     * result is deliberate and kept: `activeProfile` is read from a store built
     * synchronously on first access, so a null here does mean "no active
     * profile" — but the store is also the one path here that can throw, and
     * `ScaleSessionWorker` re-checks the address against the active profile
     * before it touches the radio, so guessing costs one no-op worker run while
     * refusing to guess would cost a weigh-in if that reasoning is ever wrong.
     */
    private fun targetAddress(context: Context, addresses: List<String>): String? =
        when (val active = activeAddressProvider(context)) {
            null -> addresses.firstOrNull()
            else -> addresses.firstOrNull { it.equals(active, ignoreCase = true) }
        }

    private fun scanResults(intent: Intent): List<ScanResult> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java,
            ).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT).orEmpty()
        }

    private companion object {
        const val TAG = "ScanBroadcastReceiver"

        /**
         * Well inside the ~10s a `goAsync()` receiver gets, and well past the
         * one local database query plus one write the enqueue actually does.
         */
        const val ENQUEUE_TIMEOUT_MILLIS = 5_000L
    }
}

private fun activeProfileAddress(context: Context): String? =
    (context.applicationContext as? BasculeApplication)
        ?.scaleProfileStore?.activeProfile?.value?.deviceAddress

private fun claimSharedCooldown(context: Context, address: String): Boolean =
    ScanEnqueueCooldown(context).claim(address)
