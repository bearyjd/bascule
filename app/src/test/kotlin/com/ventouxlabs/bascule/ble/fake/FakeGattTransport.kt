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
    /**
     * Scripted outcome per `connect()` call, consumed in order; the last entry
     * repeats once exhausted (01-plan.md §3.5's `connectStatus`/`connectDelay`
     * knobs, folded into one script since a GATT status of 0 already implies "no
     * delay worth modelling" and the timeout case has no status at all).
     */
    private val connectOutcomes: List<ConnectOutcome> = listOf(ConnectOutcome.Success),
    private val discoverOutcome: DiscoverOutcome = DiscoverOutcome.Success,
    /**
     * Characteristics whose write never gets a `WriteComplete` — for proving a
     * best-effort write (the Current Time opening write, §4.4) truly doesn't
     * block or fail the session it's part of.
     */
    private val suppressWriteCompleteFor: Set<UUID> = emptySet(),
) : GattTransport {

    private var connectAttempt = 0

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

    /**
     * Records `connect`/`close`/`discoverServices` in call order, so a test can
     * assert *when* a leak-prevention close happened relative to the next
     * connect attempt (E2), not merely that it happened (§8.10).
     */
    private val _callOrder = mutableListOf<String>()
    val callOrder: List<String> get() = _callOrder.toList()

    override fun connect() {
        _callOrder += "connect"
        connectCallCount++
        val outcome = connectOutcomes.getOrElse(connectAttempt) { connectOutcomes.last() }
        connectAttempt++
        when (outcome) {
            ConnectOutcome.Success ->
                emit(TransportEvent.ConnectionStateChanged(connected = true, status = 0))
            is ConnectOutcome.Failure ->
                emit(TransportEvent.ConnectionStateChanged(connected = false, status = outcome.status))
            is ConnectOutcome.ConnectThenDrop -> {
                emit(TransportEvent.ConnectionStateChanged(connected = true, status = 0))
                emit(TransportEvent.ConnectionStateChanged(connected = false, status = outcome.status))
            }
            ConnectOutcome.Timeout -> Unit // deliberately silent — the session's own timer must fire
        }
    }

    override fun discoverServices() {
        _callOrder += "discoverServices"
        when (val outcome = discoverOutcome) {
            DiscoverOutcome.Success -> emit(TransportEvent.ServicesDiscovered(discovered, status = 0))
            is DiscoverOutcome.Failure -> emit(TransportEvent.ServicesDiscovered(discovered, outcome.status))
            DiscoverOutcome.Timeout -> Unit
        }
    }

    /** Pushes an unsolicited adapter-off event, as `ACTION_STATE_CHANGED` does (E12). */
    fun emitAdapterOff() {
        emit(TransportEvent.AdapterOff)
    }

    /** Pushes an unsolicited disconnect, as the scale dropping mid-session does (E8) — distinct from [disconnect]. */
    fun dropConnection(status: Int = STATUS_GATT_CONN_TERMINATE_LOCAL_HOST) {
        emit(TransportEvent.ConnectionStateChanged(connected = false, status = status))
    }

    override fun write(char: UUID, bytes: ByteArray) {
        _callOrder += "write:$char"
        writesPerformed += char to bytes
        if (char !in suppressWriteCompleteFor) {
            emit(TransportEvent.WriteComplete(char, status = 0))
        }
        onWrite(char, bytes).forEach { (responseChar, value) ->
            emit(TransportEvent.CharacteristicChanged(responseChar, value))
        }
    }

    override fun enableNotifications(char: UUID) = subscribe(char, SubscriptionKind.NOTIFY)

    override fun enableIndications(char: UUID) = subscribe(char, SubscriptionKind.INDICATE)

    private fun subscribe(char: UUID, kind: SubscriptionKind) {
        _callOrder += "subscribe:$char"
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
        _callOrder += "disconnect"
        emit(TransportEvent.ConnectionStateChanged(connected = false, status = 0))
    }

    override fun close() {
        _callOrder += "close"
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
        const val STATUS_GATT_CONN_TERMINATE_LOCAL_HOST = 19
    }
}

/** Scripted outcome of one `connect()` call. */
sealed interface ConnectOutcome {
    data object Success : ConnectOutcome
    data class Failure(val status: Int) : ConnectOutcome

    /** No `ConnectionStateChanged` at all — the session's own E1 timer must fire. */
    data object Timeout : ConnectOutcome

    /**
     * `CONNECTED` immediately followed by a disconnect — E3's second shape
     * (`00-design.md` §2.3: "`CONNECTED` then immediate disconnect with status
     * 8/19/22"), and `01-plan.md` §3.6b's `device_busy.scale` fixture.
     */
    data class ConnectThenDrop(val status: Int) : ConnectOutcome
}

/** Scripted outcome of `discoverServices()`. */
sealed interface DiscoverOutcome {
    data object Success : DiscoverOutcome
    data class Failure(val status: Int) : DiscoverOutcome

    /** No `ServicesDiscovered` at all — the session's own E4 timer must fire. */
    data object Timeout : DiscoverOutcome
}
