package com.ventouxlabs.bascule.ble.session

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import org.junit.Assert.assertEquals
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
