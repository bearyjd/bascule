package com.ventouxlabs.bascule.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * The opt-in always-on bridging mode only, off by default. The primary wake path
 * is the session worker (ADR-004).
 *
 * PHASE 2 SKELETON. Implemented in Phase 3 WP-25.
 */
class BridgeForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
