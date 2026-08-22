package com.ventouxlabs.bascule.ble.session

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Expedited worker that calls setForeground(connectedDevice) and owns one GATT
 * session. This is ADR-004's path: on Android 12+ starting a foreground service
 * from a scan broadcast throws, and an expedited worker is the supported route.
 *
 * PHASE 2 SKELETON. Implemented in Phase 3 WP-08.
 */
class ScaleSessionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        TODO("WP-08: staleness abort (E10), setForeground, run GattSession, persist at EMITTED")

    companion object {
        const val UNIQUE_WORK_NAME = "scale-session"

        /** E10: past this the scale has powered off, so connecting wastes battery. */
        const val STALENESS_ABORT_MILLIS = 20_000L
    }
}
