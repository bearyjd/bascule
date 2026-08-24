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
 * PHASE 2 SKELETON. Implemented in Phase 3 WP-27.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { (context.applicationContext as BasculeApplication).scaleScanner.arm() } finally { pending.finish() }
        }
    }
}
