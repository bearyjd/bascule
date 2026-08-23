package com.ventouxlabs.bascule.ui.fake

import com.ventouxlabs.bascule.delivery.DeliveryTrigger

class FakeDeliveryTrigger : DeliveryTrigger {
    var triggerCount = 0
        private set

    override fun triggerImmediateDrain() {
        triggerCount++
    }
}
