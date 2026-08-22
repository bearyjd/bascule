package com.ventouxlabs.bascule.ble.fake

import com.ventouxlabs.bascule.ble.session.DiscoveredServices
import com.ventouxlabs.bascule.ble.session.GattTransport
import com.ventouxlabs.bascule.ble.session.SubscriptionKind
import com.ventouxlabs.bascule.ble.session.TransportEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * `FakeScaleGatt` per 01-plan.md §3.1: a scripted [GattTransport] that lets a
 * JVM test drive a whole session — connect, discovery, writes and inbound
 * indications — with no Android and no BLE radio.
 *
 * `closeCallCount` is what makes 00-design.md §8.10's "every terminal path calls
 * close() exactly once" a real assertion rather than a claim.
 */
class FakeGattTransport(
    /** Called when the session writes; return the bytes the scale indicates back. */
    private val onWrite: (UUID, ByteArray) -> List<Pair<UUID, ByteArray>> = { _, _ -> emptyList() },
    private val discovered: DiscoveredServices = DiscoveredServices(emptyMap()),
) : GattTransport {

    /**
     * Replays, deliberately. A scripted fake emits synchronously from inside
     * `connect()`/`write()`, so a session that connects before it starts
     * collecting would miss its own CONNECTED event with `replay = 0` — and
     * `tryEmit` returns true for a dropped emission, so the loss is silent. A
     * late collector must see the whole script.
     */
    private val _events = MutableSharedFlow<TransportEvent>(
        replay = REPLAY_CAPACITY,
        extraBufferCapacity = REPLAY_CAPACITY,
    )
    override val events: SharedFlow<TransportEvent> = _events

    val writesPerformed = mutableListOf<Pair<UUID, ByteArray>>()

    /**
     * Records the CCCD bit each subscription used, not merely that one happened.
     * Subscribing to an indicate-only characteristic with the notify bit yields
     * a successful descriptor write and then silence, so a fake that forgets the
     * distinction certifies code the real scale would never answer (O-04).
     */
    val subscribedCharacteristics = mutableMapOf<UUID, SubscriptionKind>()
    var connectCallCount = 0
        private set
    var closeCallCount = 0
        private set

    override fun connect() {
        connectCallCount++
        emit(TransportEvent.ConnectionStateChanged(connected = true, status = 0))
    }

    override fun discoverServices() {
        emit(TransportEvent.ServicesDiscovered(discovered, status = 0))
    }

    override fun write(char: UUID, bytes: ByteArray) {
        writesPerformed += char to bytes
        emit(TransportEvent.WriteComplete(char, status = 0))
        onWrite(char, bytes).forEach { (responseChar, value) ->
            emit(TransportEvent.CharacteristicChanged(responseChar, value))
        }
    }

    override fun enableNotifications(char: UUID) = subscribe(char, SubscriptionKind.NOTIFY)

    override fun enableIndications(char: UUID) = subscribe(char, SubscriptionKind.INDICATE)

    private fun subscribe(char: UUID, kind: SubscriptionKind) {
        subscribedCharacteristics[char] = kind
        emit(TransportEvent.SubscriptionEnabled(char, kind, status = 0))
    }

    override fun requestMtu(mtu: Int) {
        emit(TransportEvent.MtuChanged(mtu, status = 0))
    }

    override fun createBond() {
        emit(TransportEvent.BondStateChanged(BOND_BONDED))
    }

    override fun disconnect() {
        emit(TransportEvent.ConnectionStateChanged(connected = false, status = 0))
    }

    override fun close() {
        closeCallCount++
    }

    /** Pushes an unsolicited indication, as a weigh-in does. */
    fun indicate(char: UUID, value: ByteArray) {
        emit(TransportEvent.CharacteristicChanged(char, value))
    }

    private fun emit(event: TransportEvent) {
        check(_events.tryEmit(event)) { "fake transport event buffer overflow" }
    }

    private companion object {
        const val BOND_BONDED = 12
        const val REPLAY_CAPACITY = 128
    }
}
