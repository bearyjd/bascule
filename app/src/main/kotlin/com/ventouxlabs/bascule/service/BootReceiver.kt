package com.ventouxlabs.bascule.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.runNonCancelling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-arms the scan after reboot — required, because scan registrations do not
 * survive one (00-design.md §8.2).
 *
 * [arm] defaults to the real re-arm path via [BasculeApplication] — Android
 * instantiates this receiver via a no-arg reflective constructor, so
 * production behavior is unchanged. A test constructs it directly with a
 * fake instead, avoiding the real `BasculeApplication` entirely (see
 * `ScanBroadcastReceiver`'s KDoc for why that matters in this environment).
 *
 * The receiver is `android:exported="true"` because `BOOT_COMPLETED` requires
 * it, which also lets any installed app send an explicit intent here — hence
 * the action check in [onReceive] before anything else runs.
 */
class BootReceiver(
    private val arm: suspend (Context) -> Boolean = {
        (it.applicationContext as BasculeApplication).scaleScanner.arm()
    },
    private val onFailure: (Context, Throwable) -> Unit = { context, error ->
        (context.applicationContext as BasculeApplication).recordBootArmFailure(error)
    },
    /** Injectable only so the timeout path is testable without an 8-second wait. */
    private val armTimeoutMillis: Long = ARM_TIMEOUT_MILLIS,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runNonCancelling(onError = { error ->
                    if (error is Error) {
                        Log.e(TAG, "severe error contained while re-arming after boot", error)
                    }
                    // A corrupt DataStore file or a keystore fault reaching the
                    // default uncaught handler would crash the app on *every* boot.
                    onFailure(context, error)
                }) {
                    // A DataStore read that never completes must not hold the
                    // goAsync() window open until the broadcast ANR limit. Bounds
                    // the suspending path only — a blocking binder call inside
                    // arm() is not interruptible by cancellation.
                    withTimeoutOrNull(armTimeoutMillis) { arm(context) }
                }
            } finally {
                // Nullable in a unit-test lane: goAsync() only returns a
                // PendingResult when the framework put one there.
                pending?.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val ARM_TIMEOUT_MILLIS = 8_000L
    }
}
