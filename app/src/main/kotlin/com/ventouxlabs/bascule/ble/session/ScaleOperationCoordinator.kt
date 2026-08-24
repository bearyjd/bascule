package com.ventouxlabs.bascule.ble.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide serialization boundary for every scale GATT connection. */
class ScaleOperationCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withScale(
        @Suppress("UNUSED_PARAMETER") purpose: ScaleSessionPurpose,
        operation: suspend () -> T,
    ): T = mutex.withLock { operation() }

    val isBusy: Boolean get() = mutex.isLocked
}
