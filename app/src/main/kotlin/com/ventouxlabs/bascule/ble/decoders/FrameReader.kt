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
     * device's default zone, or null on underrun, an unset (zero) date, or a
     * field outside the range the characteristic defines.
     */
    fun dateTimeMillis(): Long? {
        if (remaining < DATE_TIME_BYTES) {
            underrun = true
            return null
        }
        // The bounds check above guarantees all seven reads succeed.
        val year = u16() ?: 0
        val month = u8() ?: 0
        val day = u8() ?: 0
        val hour = u8() ?: 0
        val minute = u8() ?: 0
        val second = u8() ?: 0

        // A zero year, month or day is the SIG "unknown" encoding, not a date.
        // Every other out-of-range field is a scale whose RTC was never set:
        // Calendar is lenient by default, so 0xFFFF/0xFF/0xFF would roll over
        // into an arbitrary far-future instant rather than being caught.
        if (!isInRange(year, month, day, hour, minute, second)) return null

        return Calendar.getInstance(TimeZone.getDefault()).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis
    }

    private fun isInRange(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Boolean =
        year in YEAR_RANGE && month in MONTH_RANGE && day in DAY_RANGE &&
            hour in HOUR_RANGE && minute in MINUTE_RANGE && second in SECOND_RANGE

    private companion object {
        const val BYTE_MASK = 0xFF
        const val BYTE_BITS = 8
        const val DATE_TIME_BYTES = 7

        // Bounds wide enough that no scale with a working clock is rejected,
        // narrow enough that an unset or corrupt RTC cannot become a date.
        val YEAR_RANGE = 2000..2100
        val MONTH_RANGE = 1..12
        val DAY_RANGE = 1..31
        val HOUR_RANGE = 0..23
        val MINUTE_RANGE = 0..59
        val SECOND_RANGE = 0..59
    }
}

internal fun Int.hasBit(bit: Int): Boolean = (this shr bit) and 1 == 1
