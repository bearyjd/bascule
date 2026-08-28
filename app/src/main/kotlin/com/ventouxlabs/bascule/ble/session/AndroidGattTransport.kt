package com.ventouxlabs.bascule.ble.session

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Thin Android callback adapter for [GattSession]. A fresh [BluetoothGatt] is
 * created for every [connect] call because the session deliberately closes a
 * failed instance before retrying status 133/timeouts; [connect] closes any
 * surviving instance itself so a retry branch that forgets cannot leak one.
 */
@SuppressLint("MissingPermission")
class AndroidGattTransport(
    context: Context,
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter?,
) : GattTransport {

    private val appContext = context.applicationContext
    private val _events = MutableSharedFlow<TransportEvent>(replay = EVENT_REPLAY)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()
    // Written from the GattSession coroutine (connect/close), read from
    // BluetoothGattCallback methods on a binder thread. Without @Volatile the
    // JMM gives no happens-before edge, so close()'s null could stay invisible
    // and a later write()/subscribe() could act on a closed BluetoothGatt —
    // exactly the leak 00-design.md §8.10's teardown discipline exists to stop.
    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var receiverRegistered = false
    private val pendingSubscriptions =
        ConcurrentHashMap<BluetoothGattDescriptor, Pair<UUID, SubscriptionKind>>()

    override fun connect() {
        if (adapter?.isEnabled != true) {
            emit(TransportEvent.AdapterOff)
            return
        }
        registerAdapterReceiver()
        // Defence in depth for 00-design.md §8.10: every retry branch in
        // GattSession is supposed to close() before looping back here, but a
        // branch that forgets would otherwise orphan the previous client
        // registration — and the per-app GATT client table is finite, so the
        // leak surfaces later as permanent status-133 rather than as anything
        // pointing at the branch that caused it.
        gatt?.close()
        pendingSubscriptions.clear()
        gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun discoverServices() {
        gatt?.discoverServices()
    }

    override fun write(char: UUID, bytes: ByteArray) {
        val active = gatt ?: return
        val characteristic = active.findCharacteristic(char)
        if (characteristic == null) {
            emit(TransportEvent.WriteComplete(char, STATUS_CHARACTERISTIC_MISSING))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = active.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
            if (status != BluetoothGatt.GATT_SUCCESS) emit(TransportEvent.WriteComplete(char, status))
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            if (!active.writeCharacteristic(characteristic)) {
                emit(TransportEvent.WriteComplete(char, STATUS_OPERATION_NOT_STARTED))
            }
        }
    }

    override fun enableNotifications(char: UUID) = subscribe(char, SubscriptionKind.NOTIFY)

    override fun enableIndications(char: UUID) = subscribe(char, SubscriptionKind.INDICATE)

    private fun subscribe(char: UUID, kind: SubscriptionKind) {
        val active = gatt ?: return
        val characteristic = active.findCharacteristic(char)
        if (characteristic == null) {
            emit(TransportEvent.SubscriptionEnabled(char, kind, STATUS_CHARACTERISTIC_MISSING))
            return
        }
        if (!active.setCharacteristicNotification(characteristic, true)) {
            emit(TransportEvent.SubscriptionEnabled(char, kind, STATUS_OPERATION_NOT_STARTED))
            return
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            emit(TransportEvent.SubscriptionEnabled(char, kind, STATUS_DESCRIPTOR_MISSING))
            return
        }
        val value = when (kind) {
            SubscriptionKind.NOTIFY -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            SubscriptionKind.INDICATE -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        pendingSubscriptions[descriptor] = char to kind
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = active.writeDescriptor(descriptor, value)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pendingSubscriptions.remove(descriptor)
                emit(TransportEvent.SubscriptionEnabled(char, kind, status))
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            if (!active.writeDescriptor(descriptor)) {
                pendingSubscriptions.remove(descriptor)
                emit(TransportEvent.SubscriptionEnabled(char, kind, STATUS_OPERATION_NOT_STARTED))
            }
        }
    }

    override fun requestMtu(mtu: Int) {
        gatt?.requestMtu(mtu)
    }

    override fun createBond() {
        device.createBond()
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    override fun close() {
        gatt?.close()
        gatt = null
        pendingSubscriptions.clear()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(adapterReceiver) }
            receiverRegistered = false
        }
    }

    private fun registerAdapterReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            adapterReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                        BluetoothAdapter.STATE_OFF
                    ) {
                        emit(TransportEvent.AdapterOff)
                    }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val changedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (changedDevice?.address == device.address) {
                        emit(
                            TransportEvent.BondStateChanged(
                                intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
                            ),
                        )
                    }
                }
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            emit(
                TransportEvent.ConnectionStateChanged(
                    connected = newState == BluetoothProfile.STATE_CONNECTED,
                    status = status,
                ),
            )
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val services = gatt.services.associate { service ->
                service.uuid to service.characteristics.mapTo(mutableSetOf()) { it.uuid }
            }
            emit(TransportEvent.ServicesDiscovered(DiscoveredServices(services), status))
        }

        @Deprecated("Called through API 32")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            emit(TransportEvent.CharacteristicChanged(characteristic.uuid, characteristic.value.copyOf()))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emit(TransportEvent.CharacteristicChanged(characteristic.uuid, value.copyOf()))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            emit(TransportEvent.WriteComplete(characteristic.uuid, status))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val (char, kind) = pendingSubscriptions.remove(descriptor) ?: return
            emit(TransportEvent.SubscriptionEnabled(char, kind, status))
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            emit(TransportEvent.MtuChanged(mtu, status))
        }
    }

    private fun BluetoothGatt.findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        services.asSequence().flatMap { it.characteristics.asSequence() }.firstOrNull { it.uuid == uuid }

    private fun emit(event: TransportEvent) {
        _events.tryEmit(event)
    }

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val EVENT_REPLAY = 128
        const val STATUS_OPERATION_NOT_STARTED = -1
        const val STATUS_DESCRIPTOR_MISSING = -2

        /**
         * The requested characteristic is absent from the discovered services,
         * so the operation was never attempted. Emitted rather than returned
         * silently: GattSession's awaits are event-driven, and silence makes a
         * firmware/profile mismatch arrive as a plain timeout minutes later.
         */
        const val STATUS_CHARACTERISTIC_MISSING = -3
    }
}
