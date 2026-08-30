package com.ventouxlabs.bascule.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A registered scale with capture switched off looks identical to "nobody has
 * weighed in" — the app is silently doing nothing, which is the worst state
 * for a background-capture app to be in without saying so.
 */
class HistoryCaptureStateTest {

    @Test
    fun noScalePairedIsDistinctFromCaptureOff() {
        assertEquals(CaptureState.NO_SCALE, captureStateOf(pairedAddress = null, captureEnabled = false))
        assertEquals(CaptureState.NO_SCALE, captureStateOf(pairedAddress = null, captureEnabled = true))
    }

    @Test
    fun aPairedScaleWithCaptureOffReportsOff() {
        assertEquals(
            CaptureState.OFF,
            captureStateOf(pairedAddress = "E7:DB:51:F1:36:91", captureEnabled = false),
        )
    }

    @Test
    fun aPairedScaleWithCaptureOnReportsWatching() {
        assertEquals(
            CaptureState.WATCHING,
            captureStateOf(pairedAddress = "E7:DB:51:F1:36:91", captureEnabled = true),
        )
    }

    @Test
    fun aBlankStoredAddressIsTreatedAsNoScale() {
        assertEquals(CaptureState.NO_SCALE, captureStateOf(pairedAddress = "", captureEnabled = true))
        assertEquals(CaptureState.NO_SCALE, captureStateOf(pairedAddress = "   ", captureEnabled = true))
    }
}
