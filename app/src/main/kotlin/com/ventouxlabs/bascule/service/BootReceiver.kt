package com.ventouxlabs.bascule.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ventouxlabs.bascule.BasculeApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms the scan after reboot — required, because scan registrations do not
 * survive one (00-design.md §8.2).
 *
 * [arm] defaults to the real re-arm path via [BasculeApplication] — Android
 * instantiates this receiver via a no-arg reflective constructor, so
 * production behavior is unchanged. A test constructs it directly with a
 * fake instead, avoiding the real `BasculeApplication` entirely (see
 * `ScanBroadcastReceiver`'s KDoc for why that matters in this environment).
 */
class BootReceiver(
    private val arm: suspend (Context) -> Boolean = {
        (it.applicationContext as BasculeApplication).scaleScanner.arm()
    },
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { arm(context) } finally { pending.finish() }
        }
    }
}
