package com.ventouxlabs.bascule.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the scan PendingIntent and does one thing: enqueue the session
 * worker. It never connects — a BroadcastReceiver is dead ~10 s after
 * onReceive and a BF720 session runs tens of seconds (ADR-004).
 *
 * PHASE 2 SKELETON. Implemented in Phase 3 WP-08.
 */
class ScanBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TODO("WP-08: enqueue expedited ScaleSessionWorker under unique work 'scale-session'")
    }
}
