package com.ventouxlabs.bascule.diagnostics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric rather than a plain JVM test for the same reason
 * `ScanEnqueueCooldownTest` is: the record has to outlive the worker process
 * that wrote it, which is the entire point, so the [android.content.SharedPreferences]
 * behaviour is the behaviour under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CaptureAttemptLogTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun log() = SharedPreferencesCaptureAttemptLog(context)

    @Test
    fun thereIsNoAttemptBeforeOneHasBeenRecorded() {
        assertNull(log().last.value)
    }

    @Test
    fun anAttemptIsReadableImmediatelyAfterBeingRecorded() {
        val log = log()

        log.record(CaptureOutcome.NO_READING, atMillis = 1_000L)

        assertEquals(LastCaptureAttempt(1_000L, CaptureOutcome.NO_READING), log.last.value)
    }

    /**
     * The property the whole class exists for: the writer is a worker whose
     * process may be gone by the time the user opens the app, so the record has
     * to survive a completely fresh instance.
     */
    @Test
    fun anAttemptSurvivesTheInstanceThatRecordedIt() {
        log().record(CaptureOutcome.MISSED_THE_WINDOW, atMillis = 2_000L)

        assertEquals(
            LastCaptureAttempt(2_000L, CaptureOutcome.MISSED_THE_WINDOW),
            log().last.value,
        )
    }

    @Test
    fun aLaterAttemptReplacesTheEarlierOne() {
        val log = log()
        log.record(CaptureOutcome.NO_READING, atMillis = 1_000L)

        log.record(CaptureOutcome.CAPTURED, atMillis = 3_000L)

        assertEquals(LastCaptureAttempt(3_000L, CaptureOutcome.CAPTURED), log.last.value)
    }

    /**
     * A live instance must see a write made through another one — the UI is
     * subscribed while the worker records.
     */
    @Test
    fun anOpenInstanceSeesAWriteMadeThroughAnother() {
        val reader = log()

        log().record(CaptureOutcome.CAPTURED, atMillis = 4_000L)

        assertEquals(LastCaptureAttempt(4_000L, CaptureOutcome.CAPTURED), reader.last.value)
    }

    /**
     * A diagnostic surface must never be the reason a screen fails to render.
     * An outcome name written by a newer build is the realistic way this
     * happens, after a downgrade.
     */
    @Test
    fun anUnrecognisedOutcomeReadsAsNoAttemptRatherThanThrowing() {
        context.getSharedPreferences("capture_attempts", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("last_outcome", "SOMETHING_A_NEWER_BUILD_WROTE")
            .putLong("last_at_millis", 5_000L)
            .commit()

        assertNull(log().last.value)
    }
}
