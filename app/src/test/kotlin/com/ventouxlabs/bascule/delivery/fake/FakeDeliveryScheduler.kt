package com.ventouxlabs.bascule.delivery.fake

import com.ventouxlabs.bascule.delivery.DeliveryScheduler

/** Records what [com.ventouxlabs.bascule.delivery.DeliveryWorker]'s post-drain step asked for; nothing is ever run. */
class FakeDeliveryScheduler : DeliveryScheduler {
    private val _retryKickDelays = mutableListOf<Long>()
    val retryKickDelays: List<Long> get() = _retryKickDelays

    var continuations = 0
        private set

    var immediateDrains = 0
        private set

    var periodicEnsures = 0
        private set

    override fun triggerImmediateDrain() {
        immediateDrains++
    }

    override fun ensurePeriodicDrain() {
        periodicEnsures++
    }

    override fun enqueueContinuation() {
        continuations++
    }

    override fun scheduleRetryKick(delayMillis: Long) {
        _retryKickDelays += delayMillis
    }
}
