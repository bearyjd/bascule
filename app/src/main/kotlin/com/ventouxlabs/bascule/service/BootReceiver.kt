package com.ventouxlabs.bascule.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the scan after reboot — required, because scan registrations do not
 * survive one (00-design.md §8.2).
 *
 * PHASE 2 SKELETON. Implemented in Phase 3 WP-27.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
