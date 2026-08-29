package com.ventouxlabs.bascule.ble.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide serialization boundary for every scale GATT connection. There
 * is exactly one physical scale, so a blanket mutex is correct today — no
 * purpose gets special treatment beyond exclusion. [busyWith] surfaces which
 * purpose currently holds the scale, so a caller that finds it non-null (e.g.
 * the Scale tab, or a session worker deciding whether to retry) can say what
 * it is waiting on rather than just that it is waiting.
 */
class ScaleOperationCoordinator {
    private val mutex = Mutex()

    @Volatile
    private var currentPurpose: ScaleSessionPurpose? = null

    suspend fun <T> withScale(
        purpose: ScaleSessionPurpose,
        operation: suspend () -> T,
    ): T = mutex.withLock {
        currentPurpose = purpose
        try {
            operation()
        } finally {
            currentPurpose = null
        }
    }

    val busyWith: ScaleSessionPurpose? get() = currentPurpose
}
