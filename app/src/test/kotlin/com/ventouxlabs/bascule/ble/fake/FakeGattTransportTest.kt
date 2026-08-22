package com.ventouxlabs.bascule.ble.fake

import app.cash.turbine.test
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.session.SubscriptionKind
import com.ventouxlabs.bascule.ble.session.TransportEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fake is test infrastructure, so it gets its own tests (01-plan.md §3).
 *
 * The property below is the one that matters and the one a fake gets wrong
 * silently: a session calls `connect()` and only then starts collecting, so an
 * event emitted from inside `connect()` must still be delivered. With a
 * zero-replay `MutableSharedFlow` it is dropped and `tryEmit` still reports
 * success — which would present in Phase 3 as WP-06 hanging on a connect
 * timeout that never had a cause.
 */
class FakeGattTransportTest {

    @Test
    fun eventsEmittedBeforeCollectionStartsAreStillDelivered() = runTest {
        val transport = FakeGattTransport()

        transport.connect()

        transport.events.test {
            assertEquals(
                TransportEvent.ConnectionStateChanged(connected = true, status = 0),
                awaitItem(),
            )
        }
    }

    @Test
    fun emitsEventsInScriptedOrder() = runTest {
        val transport = FakeGattTransport(
            onWrite = { char, _ -> listOf(char to Bf720Capture.consentSuccess()) },
        )

        transport.connect()
        transport.write(SigWeightProfile.USER_CONTROL_POINT, byteArrayOf(0x02))

        transport.events.test {
            assertTrue(awaitItem() is TransportEvent.ConnectionStateChanged)
            assertTrue(awaitItem() is TransportEvent.WriteComplete)
            val indication = awaitItem() as TransportEvent.CharacteristicChanged
            assertArrayEquals(Bf720Capture.consentSuccess(), indication.value)
        }
    }

    @Test
    fun recordsWritesAndSubscriptionsForAssertion() = runTest {
        val transport = FakeGattTransport()

        transport.write(SigWeightProfile.USER_CONTROL_POINT, byteArrayOf(0x01, 0x34, 0x12))
        transport.enableIndications(SigWeightProfile.WEIGHT_MEASUREMENT)

        assertEquals(1, transport.writesPerformed.size)
        assertArrayEquals(byteArrayOf(0x01, 0x34, 0x12), transport.writesPerformed.single().second)
        // The kind is recorded, not just the fact: the BF720's measurement
        // characteristics are indicate-only, and a notify-bit CCCD write would
        // succeed and then produce silence (O-04).
        assertEquals(
            mapOf(SigWeightProfile.WEIGHT_MEASUREMENT to SubscriptionKind.INDICATE),
            transport.subscribedCharacteristics,
        )
    }

    @Test
    fun notifyAndIndicateAreDistinguishableSubscriptions() = runTest {
        val transport = FakeGattTransport()

        transport.enableNotifications(SigWeightProfile.WEIGHT_MEASUREMENT)

        assertEquals(
            SubscriptionKind.NOTIFY,
            transport.subscribedCharacteristics[SigWeightProfile.WEIGHT_MEASUREMENT],
        )
    }

    @Test
    fun closeIsCounted() = runTest {
        val transport = FakeGattTransport()

        transport.close()
        transport.close()

        // 00-design.md §8.10's "exactly once" invariant is only assertable
        // because the fake counts.
        assertEquals(2, transport.closeCallCount)
    }
}
