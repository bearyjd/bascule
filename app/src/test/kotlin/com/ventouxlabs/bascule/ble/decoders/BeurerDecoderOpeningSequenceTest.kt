package com.ventouxlabs.bascule.ble.decoders

import com.ventouxlabs.bascule.ble.fake.Bf720Capture
import com.ventouxlabs.bascule.ble.session.DiscoveredServices
import com.ventouxlabs.bascule.ble.session.GattOp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * `openingSequence()` — the Current Time write, `00-design.md` §4.4. Exact
 * byte coverage was the gap review found in WP-07: `clock` existed on
 * `GattSession` specifically to make this write deterministic in a JVM test,
 * and nothing asserted the resulting bytes.
 */
class BeurerDecoderOpeningSequenceTest {

    private val discoveredWithCurrentTime = DiscoveredServices(
        mapOf(SigWeightProfile.CURRENT_TIME_SERVICE to setOf(SigWeightProfile.CURRENT_TIME)),
    )

    private val discoveredWithoutCurrentTime = DiscoveredServices(emptyMap())

    /**
     * `Bf720Capture.expectedTimestampMillis` is 2026-08-22 16:51:01 — a
     * **Saturday**. Bytes below are computed independently (not by calling the
     * code under test) and cross-checked against `Bf720Capture.WEIGHT_MEASUREMENT`'s
     * own captured timestamp field, which is the same date/time from the same
     * live session and shares the year/month/day/hour/minute/second bytes:
     * `0xea 0x07 0x08 0x16 0x10 0x33 0x01` at offsets 3-9 of that frame
     * (`03-hardware-validation.md` §5's "timestamp matches the Current Time
     * value written moments earlier" is this exact correspondence).
     */
    @Test
    fun encodesTheConfirmedCaptureTimestampExactly() {
        val decoder = BeurerDecoder()

        val ops = decoder.openingSequence(discoveredWithCurrentTime, Bf720Capture.expectedTimestampMillis)

        assertEquals(1, ops.size)
        val write = ops.single() as GattOp.Write
        assertEquals(SigWeightProfile.CURRENT_TIME, write.char)
        assertArrayEquals(
            byteArrayOf(0xea.toByte(), 0x07, 0x08, 0x16, 0x10, 0x33, 0x01, SATURDAY, 0x00, 0x00),
            write.bytes,
        )
    }

    /** Pins the `Calendar.SUNDAY -&gt; 7` remap — the easiest field to get backwards. */
    @Test
    fun encodesSundayAsBleDayOfWeekSeven() {
        val decoder = BeurerDecoder()
        val sunday4thJanuary2026 = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.JANUARY, 4, 0, 0, 0)
        }.timeInMillis

        val ops = decoder.openingSequence(discoveredWithCurrentTime, sunday4thJanuary2026)

        val write = ops.single() as GattOp.Write
        assertArrayEquals(
            byteArrayOf(0xea.toByte(), 0x07, 0x01, 0x04, 0x00, 0x00, 0x00, SUNDAY, 0x00, 0x00),
            write.bytes,
        )
    }

    @Test
    fun isBestEffortNotAckDriven() {
        val decoder = BeurerDecoder()

        val write = decoder.openingSequence(discoveredWithCurrentTime, Bf720Capture.expectedTimestampMillis)
            .single() as GattOp.Write

        assertTrue("the CTS write is a plain write, not a UCP-ack step", write.expectAckWithin == null)
    }

    @Test
    fun skipsTheWriteWhenTheDeviceDoesNotExposeCurrentTime() {
        val decoder = BeurerDecoder()

        val ops = decoder.openingSequence(discoveredWithoutCurrentTime, Bf720Capture.expectedTimestampMillis)

        assertEquals(emptyList<GattOp>(), ops)
    }

    private companion object {
        const val SATURDAY = 0x06.toByte()
        const val SUNDAY = 0x07.toByte()
    }
}
