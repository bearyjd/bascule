package com.ventouxlabs.bascule.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** No code reads the Battery Level characteristic (2A19), so the UI must not promise it. */
class ScaleStatusTextTest {
    @Test
    fun `status note does not claim battery level is available`() {
        assertFalse(SCALE_STATUS_NOTE.contains("battery", ignoreCase = true))
    }

    @Test
    fun `status note describes live status only`() {
        assertEquals("Live status is only available while the app is connected to your scale.", SCALE_STATUS_NOTE)
    }
}
