package com.ventouxlabs.bascule.ble.decoders

import java.util.Calendar
import java.util.TimeZone

/**
 * Bounds-checked little-endian cursor over a notification payload.
 *
 * Every read is bounds-checked and signals underrun by returning null rather
 * than throwing: notification callbacks run on a binder thread, where an escaped
 * exception kills the process (00-design.md §8.9, E11).
 */
internal class FrameReader(private val bytes: ByteArray) {
    private var offset: Int = 0

    /**
     * Set by the first read that ran past the end of the buffer. Callers check
     * it once after decoding rather than at every field, so "field absent" and
     * "frame truncated" stay distinguishable: an absent optional field is a null
     * that was never read, an underrun is this flag.
     */
    var underrun: Boolean = false
        private set

    val remaining: Int get() = bytes.size - offset

    fun u8(): Int? {
        if (remaining < 1) {
            underrun = true
            return null
        }
        return bytes[offset++].toInt() and BYTE_MASK
    }

    fun u16(): Int? {
        if (remaining < 2) {
            underrun = true
            return null
        }
        val low = bytes[offset].toInt() and BYTE_MASK
        val high = bytes[offset + 1].toInt() and BYTE_MASK
        offset += 2
        return (high shl BYTE_BITS) or low
    }

    /**
     * SIG `org.bluetooth.characteristic.date_time`: year uint16, then month,
     * day, hours, minutes, seconds as uint8. Returns epoch millis in the
     * device's default zone, or null on underrun or an unset (zero) date.
     */
    fun dateTimeMillis(): Long? {
        if (remaining < DATE_TIME_BYTES) {
            underrun = true
            return null
        }
        // The bounds check above guarantees all seven reads succeed.
        val year = u16() ?: return null
        val month = u8() ?: return null
        val day = u8() ?: return null
        val hour = u8() ?: 0
        val minute = u8() ?: 0
        val second = u8() ?: 0

        // A zero year, month or day is the SIG "unknown" encoding, not a date.
        if (year == 0 || month == 0 || day == 0) return null

        return Calendar.getInstance(TimeZone.getDefault()).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val BYTE_BITS = 8
        const val DATE_TIME_BYTES = 7
    }
}

internal fun Int.hasBit(bit: Int): Boolean = (this shr bit) and 1 == 1
