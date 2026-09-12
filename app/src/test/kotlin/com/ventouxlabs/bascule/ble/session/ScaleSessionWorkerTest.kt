package com.ventouxlabs.bascule.ble.session

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.ventouxlabs.bascule.diagnostics.CaptureOutcome
import com.ventouxlabs.bascule.ui.fake.FakeCaptureAttemptLog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers only [ScaleSessionWorker.doWork]'s branches that return *before*
 * `applicationContext as BasculeApplication` (WP-08's E10 staleness abort
 * and the permission check — `docs/prp/01-plan.md:893-895`).
 *
 * Not covered here: the adapter-off retry, profile-mismatch success, and
 * outcome-to-`Result` mapping branches, which all run *after* that cast.
 * `BasculeApplication.onCreate()` eagerly touches `WorkManager`/
 * `EncryptedSharedPreferences`, neither available in this environment's
 * Robolectric setup (see `ScanBroadcastReceiverTest`'s KDoc and this test's
 * companion plan doc, Task 2's GOTCHA) — using the real `BasculeApplication`
 * here would hit the same crash, and using a plain `Application` breaks the
 * cast these later branches need. Closing that gap means either making
 * `BasculeApplication`'s dependencies swappable (a `WorkerFactory`, or
 * `open`/overridable properties) or accepting a real device — a materially
 * bigger decision than this pass's `ScaleSessionEnqueuer` extraction, and
 * deliberately left open rather than done unilaterally. See
 * `.claude/PRPs/plans/scale-admin-testing-completeness.plan.md` Task 4.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScaleSessionWorkerTest {

    private fun worker(seenAtMillis: Long? = System.currentTimeMillis()): ScaleSessionWorker {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val data = Data.Builder().putString(ScaleSessionWorker.KEY_ADDRESS, DEVICE_ADDRESS)
        seenAtMillis?.let { data.putLong(ScaleSessionWorker.KEY_SEEN_AT, it) }
        return TestListenableWorkerBuilder<ScaleSessionWorker>(context).setInputData(data.build()).build()
    }

    /**
     * The call site that records an attempt, finally reachable: substituting
     * [ScaleSessionWorker.captureAttemptLogProvider] gets past
     * `applicationContext as? BasculeApplication`, which is null under the plain
     * `Application` this lane uses and made the whole body a silent no-op.
     *
     * What it asserts is the part that was taken on inspection until now — that
     * the *fine-grained* reason is what reaches the log, not just the coarse
     * outcome. `HANDSHAKE_FAILED` and `MISSED` both map to `NO_READING`, so an
     * assertion on the outcome alone would pass either way.
     */
    @Test
    fun recordingAnAttemptStoresTheFineGrainedReasonNotJustTheOutcome() = runTest {
        val log = FakeCaptureAttemptLog()
        val worker = worker()
        worker.captureAttemptLogProvider = { log }

        worker.recordAttempt(SessionExitReason.HANDSHAKE_FAILED)

        val recorded = log.last.value
        assertEquals(CaptureOutcome.NO_READING, recorded?.outcome)
        assertEquals(
            "the reason NO_READING throws away is the whole point of storing it",
            SessionExitReason.HANDSHAKE_FAILED.name,
            recorded?.technicalReason,
        )
    }

    /**
     * The discriminating pair: two reasons that collapse to the same outcome
     * must still be told apart afterwards. Without this, passing a constant —
     * or the outcome's own name — would satisfy the test above.
     */
    @Test
    fun twoReasonsSharingAnOutcomeAreStillDistinguishable() = runTest {
        val handshake = FakeCaptureAttemptLog()
        val missed = FakeCaptureAttemptLog()
        worker().also { it.captureAttemptLogProvider = { handshake } }
            .recordAttempt(SessionExitReason.HANDSHAKE_FAILED)
        worker().also { it.captureAttemptLogProvider = { missed } }
            .recordAttempt(SessionExitReason.MISSED)

        assertEquals(
            "both are NO_READING, which is exactly why the outcome is not enough",
            handshake.last.value?.outcome,
            missed.last.value?.outcome,
        )
        assertNotEquals(
            "and the stored reason is what separates them",
            handshake.last.value?.technicalReason,
            missed.last.value?.technicalReason,
        )
    }

    @Test
    fun missingAddressFails() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val noAddress = TestListenableWorkerBuilder<ScaleSessionWorker>(context).build()

        val result = noAddress.startWork().get()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun missingSeenAtTimestampSucceedsWithoutAttemptingASession() {
        val result = worker(seenAtMillis = null).startWork().get()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun stalenessPastTheAbortThresholdSucceedsWithoutAttemptingASession() {
        val staleSeenAt = System.currentTimeMillis() - ScaleSessionWorker.STALENESS_ABORT_MILLIS - 1
        val result = worker(seenAtMillis = staleSeenAt).startWork().get()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun withinTheStalenessWindowWithoutBluetoothConnectPermissionFailsOnApi31Plus() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)

        val result = worker().startWork().get()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    private companion object {
        const val DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
