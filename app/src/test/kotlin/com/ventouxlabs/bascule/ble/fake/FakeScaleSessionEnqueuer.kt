package com.ventouxlabs.bascule.ble.fake

import com.ventouxlabs.bascule.ble.session.ScaleSessionEnqueuer

/**
 * In-memory [ScaleSessionEnqueuer] — no WorkManager, no Robolectric, no Room.
 * Synchronized because `ScanBroadcastReceiver` now enqueues from a coroutine on
 * `Dispatchers.IO` while the test thread reads [calls].
 */
class FakeScaleSessionEnqueuer : ScaleSessionEnqueuer {
    data class Call(val address: String, val seenAtMillis: Long)

    private val lock = Any()
    private val _calls = mutableListOf<Call>()
    private val pending = mutableListOf<() -> Unit>()

    val calls: List<Call> get() = synchronized(lock) { _calls.toList() }

    /**
     * When true, completion is held rather than reported, standing in for a
     * WorkManager write that has not landed yet — release it with
     * [completePending], or leave it held to exercise a caller's timeout.
     */
    @Volatile
    var deferCompletion: Boolean = false

    override fun enqueue(address: String, seenAtMillis: Long, onEnqueued: () -> Unit) {
        synchronized(lock) {
            _calls += Call(address, seenAtMillis)
            if (deferCompletion) {
                pending += onEnqueued
                return
            }
        }
        onEnqueued()
    }

    fun completePending() {
        val due = synchronized(lock) { pending.toList().also { pending.clear() } }
        due.forEach { it() }
    }
}
