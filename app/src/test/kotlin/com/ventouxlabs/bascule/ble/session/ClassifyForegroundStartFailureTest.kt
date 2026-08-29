package com.ventouxlabs.bascule.ble.session

import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounterKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [classifyForegroundStartFailure] is a pure, `Context`-free extraction from
 * [ScaleSessionWorker.enterForeground] — the one piece of that method's logic
 * this environment can test directly, since `enterForeground` itself sits
 * behind `applicationContext as BasculeApplication` (see
 * `ScaleSessionWorkerTest`'s KDoc; devil's-advocate review, testing gaps
 * round 4).
 */
class ClassifyForegroundStartFailureTest {

    @Test
    fun aSecurityExceptionMapsToMissedQuota() {
        assertEquals(
            DiagnosticsCounterKey.MISSED_QUOTA,
            classifyForegroundStartFailure(SecurityException("permission revoked")),
        )
    }

    @Test
    fun anIllegalStateExceptionMapsToMissedQuota() {
        assertEquals(
            DiagnosticsCounterKey.MISSED_QUOTA,
            classifyForegroundStartFailure(IllegalStateException("not allowed to start foreground")),
        )
    }

    @Test
    fun anUnrelatedRuntimeExceptionStillMapsToMissedQuota() {
        // Pinned so a future change that narrows the mapping does so
        // deliberately rather than by accident.
        assertEquals(
            DiagnosticsCounterKey.MISSED_QUOTA,
            classifyForegroundStartFailure(RuntimeException("anything else")),
        )
    }
}
