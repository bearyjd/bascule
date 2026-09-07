package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.ble.fake.FakeGattTransport
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Names the one invariant that lives entirely inside the extracted ladder,
 * so a later edit to the seam cannot quietly break it. Everything else the
 * ladder does is pinned through `GattSessionConnectTest` via the session's
 * public constructor, which is the stronger guard for wiring.
 */
class ConnectLadderTest {

    private val ladder = ConnectLadder(FakeGattTransport())

    /**
     * A retry boundary discards whatever an already-closed attempt left in
     * the channel — except an adapter-off, which is session state, not attempt
     * residue, and is put back so the next wait step still sees it.
     */
    @Test
    fun drainingKeepsAnAdapterOffAndDiscardsEverythingBeforeIt() {
        val events = Channel<TransportEvent>(Channel.UNLIMITED)
        events.trySend(TransportEvent.ConnectionStateChanged(connected = false, status = 133))
        events.trySend(TransportEvent.MtuChanged(23, 0))
        events.trySend(TransportEvent.AdapterOff)

        ladder.drainStaleEvents(events)

        assertEquals(TransportEvent.AdapterOff, events.tryReceive().getOrNull())
        assertNull("nothing but the adapter-off may survive a drain", events.tryReceive().getOrNull())
    }

    @Test
    fun drainingWithNoAdapterOffEmptiesTheChannel() {
        val events = Channel<TransportEvent>(Channel.UNLIMITED)
        events.trySend(TransportEvent.ConnectionStateChanged(connected = false, status = 133))
        events.trySend(TransportEvent.MtuChanged(23, 0))

        ladder.drainStaleEvents(events)

        assertNull(events.tryReceive().getOrNull())
    }
}
