package com.ventouxlabs.bascule.ble.fake

import com.ventouxlabs.bascule.ble.session.ScaleSessionEnqueuer

/** In-memory [ScaleSessionEnqueuer] — no WorkManager, no Robolectric, no Room. */
class FakeScaleSessionEnqueuer : ScaleSessionEnqueuer {
    data class Call(val address: String, val seenAtMillis: Long)

    private val _calls = mutableListOf<Call>()
    val calls: List<Call> get() = _calls.toList()

    override fun enqueue(address: String, seenAtMillis: Long) {
        _calls += Call(address, seenAtMillis)
    }
}
