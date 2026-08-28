package com.ventouxlabs.bascule.delivery

import androidx.work.WorkManager

/**
 * A single-method seam around [WorkManager] so callers that only need to kick
 * off an immediate drain (`00-design.md` §8.6) can be unit-tested against a
 * fake, the same way [com.ventouxlabs.bascule.ble.session.ConsentStore] and
 * [com.ventouxlabs.bascule.data.ConfigStore] are — a ViewModel test should not
 * have to stand up a WorkManager to assert that a save asks for a drain.
 *
 * Narrower than [DeliveryScheduler] on purpose: a caller that can only trigger
 * cannot reach [DeliveryScheduler.enqueueContinuation], which is not safe to
 * call from outside a running drain.
 */
interface DeliveryTrigger {
    /** Enqueues [DeliveryWorker] under its unique work name, keeping any run already in flight. */
    fun triggerImmediateDrain()
}
