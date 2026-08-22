package com.ventouxlabs.bascule.ble.session

import java.util.UUID
import kotlin.time.Duration

/**
 * A single operation the session executes against [GattTransport]. Decoders
 * describe conversations in terms of these and perform no I/O themselves
 * (00-design.md §2.6).
 */
sealed interface GattOp {
    class Write(
        val char: UUID,
        val bytes: ByteArray,
        val expectAckWithin: Duration?,
    ) : GattOp {
        override fun equals(other: Any?): Boolean =
            other is Write &&
                char == other.char &&
                bytes.contentEquals(other.bytes) &&
                expectAckWithin == other.expectAckWithin

        override fun hashCode(): Int =
            (char.hashCode() * PRIME + bytes.contentHashCode()) * PRIME + expectAckWithin.hashCode()

        override fun toString(): String = "Write(char=$char, bytes=${bytes.size}B)"

        private companion object {
            const val PRIME = 31
        }
    }

    data class EnableNotifications(val char: UUID) : GattOp

    /**
     * Distinct from [EnableNotifications]: the Weight Scale, Body Composition
     * and User Data measurement characteristics on the BF720 are *indicate*,
     * which writes a different CCCD value.
     */
    data class EnableIndications(val char: UUID) : GattOp

    data class RequestMtu(val mtu: Int) : GattOp
}
