package com.ventouxlabs.bascule.ble.session

import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * Interface over `BluetoothGatt` (01-plan.md §3.1). The Android implementation
 * carries no logic — it translates callbacks into [TransportEvent] and forwards
 * calls — so substituting a fake in tests leaves no untested surface.
 */
interface GattTransport {
    val events: SharedFlow<TransportEvent>

    fun connect()
    fun discoverServices()
    fun write(char: UUID, bytes: ByteArray)

    /**
     * Notify and indicate are different ATT operations enabled by writing
     * different bits to the same CCCD. Keeping them as two calls rather than one
     * `subscribe(char)` is deliberate: the BF720's measurement characteristics
     * are indicate-only, and a transport that silently picked the notify bit
     * would connect, subscribe, and receive nothing — the exact symptom
     * docs/prp/03-hardware-validation.md spent a hardware session chasing.
     */
    fun enableNotifications(char: UUID)
    fun enableIndications(char: UUID)
    fun requestMtu(mtu: Int)
    fun createBond()
    fun disconnect()
    fun close()
}

sealed interface TransportEvent {
    data class ConnectionStateChanged(val connected: Boolean, val status: Int) : TransportEvent
    data class ServicesDiscovered(val services: DiscoveredServices, val status: Int) : TransportEvent
    class CharacteristicChanged(val char: UUID, val value: ByteArray) : TransportEvent
    data class WriteComplete(val char: UUID, val status: Int) : TransportEvent
    data class SubscriptionEnabled(
        val char: UUID,
        val kind: SubscriptionKind,
        val status: Int,
    ) : TransportEvent
    data class MtuChanged(val mtu: Int, val status: Int) : TransportEvent
    data class BondStateChanged(val state: Int) : TransportEvent
    data object AdapterOff : TransportEvent
}

/** Which CCCD bit a subscription was established with. */
enum class SubscriptionKind { NOTIFY, INDICATE }

/** Services and their characteristics as reported by discovery. */
data class DiscoveredServices(val services: Map<UUID, Set<UUID>>) {
    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        services[service]?.contains(characteristic) == true

    fun containsAll(required: Set<UUID>): Boolean = services.keys.containsAll(required)
}
