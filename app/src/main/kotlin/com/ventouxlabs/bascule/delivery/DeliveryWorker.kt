package com.ventouxlabs.bascule.delivery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Drains PENDING rows. Runs independently of the session process so a killed
 * service never strands a captured reading (00-design.md §8.1).
 *
 * PHASE 2 SKELETON. Implemented in Phase 3 WP-21.
 */
class DeliveryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = TODO("WP-21: drain PENDING rows with backoff")

    companion object {
        const val UNIQUE_WORK_NAME = "delivery-drain"
    }
}
