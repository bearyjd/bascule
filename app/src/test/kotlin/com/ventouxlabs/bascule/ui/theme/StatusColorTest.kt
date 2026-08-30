package com.ventouxlabs.bascule.ui.theme

import androidx.compose.ui.graphics.Color
import com.ventouxlabs.bascule.data.ReadingStatus
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class StatusColorTest {

    /** WCAG relative luminance, then the standard (L1+0.05)/(L2+0.05) ratio. */
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun everyStatusIsLegibleInBothThemes() {
        for (status in ReadingStatus.entries) {
            for (dark in listOf(false, true)) {
                val palette = statusPalette(status, dark)
                val ratio = contrast(palette.container, palette.content)
                assertTrue(
                    "$status (dark=$dark) contrast $ratio is below the WCAG AA 4.5:1 floor",
                    ratio >= 4.5,
                )
            }
        }
    }

    /**
     * The point of pinning these: a delivery that needs the user to act must
     * never look like one that succeeded, on any wallpaper.
     */
    @Test
    fun blockedIsVisuallyDistinctFromSent() {
        for (dark in listOf(false, true)) {
            assertNotEquals(
                statusPalette(ReadingStatus.SENT, dark).container,
                statusPalette(ReadingStatus.BLOCKED_AUTH, dark).container,
            )
        }
    }
}
