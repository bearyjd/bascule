package com.ventouxlabs.bascule.service

import android.Manifest
import android.app.Application
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.ble.fake.FakeScaleSessionEnqueuer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowService

/**
 * S4: the permission gate has to run *before* `startForeground`, because on API
 * 34+ a `connectedDevice` foreground service may only start while the app holds
 * a Bluetooth runtime permission — checking afterwards means the
 * `SecurityException` has already been thrown out of `onCreate`.
 *
 * Only the denied path is covered. The granted path runs on into
 * `startActiveScan`, which casts to the real `BasculeApplication` and would
 * need a live Keystore for its encrypted stores — red for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BridgeForegroundServiceTest {

    @Test
    fun onCreateWithoutScanPermissionStopsInsteadOfGoingForeground() {
        val controller = Robolectric.buildService(BridgeForegroundService::class.java)

        controller.create()

        val shadow: ShadowService = shadowOf(controller.get())
        assertNull("startForeground must not run before the permission check", shadow.lastForegroundNotification)
        assertTrue("the service must stop itself rather than idle", shadow.isStoppedBySelf)
    }

    /**
     * pr-1-review-patterns.md P20. The dispatch path this service owns is now
     * reachable from a test, the way `ScanBroadcastReceiver`'s already was:
     * substituting [enqueuerFactory] keeps a real `WorkManager` out of the JVM
     * lane. The service is built but never `create()`d — `onCreate` runs the
     * permission gate and `startActiveScan`'s cast to the real
     * `BasculeApplication`, neither of which this behavior depends on.
     */
    /**
     * The re-arm exists because an external stop/start cannot do this job:
     * `Context.stopService` is asynchronous, so the restart can be delivered to
     * the still-live instance, and `onStartCommand` never called
     * `startActiveScan`. Re-arming in place removes the race — but it must stay
     * a *re-arm*, not a second start, so it may not disturb the restart mode a
     * bounded window established. `START_STICKY` here would mean a mid-window
     * process kill restarts the scan unbounded, with no timer to end it: the
     * exact failure `onStartCommand`'s KDoc already guards for bounded starts.
     */
    @Test
    fun aRearmPreservesTheRestartModeOfABoundedStart() {
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.activeAddressProvider = { null }
        service.boundStopScheduler = { _, _ -> }

        val bounded = service.onStartCommand(
            Intent().putExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, 120_000L),
            0,
            1,
        )
        val rearmed = service.onStartCommand(
            Intent().putExtra(BridgeForegroundService.EXTRA_REARM_SCAN, true),
            0,
            2,
        )

        assertEquals(Service.START_NOT_STICKY, bounded)
        assertEquals("a re-arm must not promote a bounded window to sticky", bounded, rearmed)
    }

    /**
     * The bounded window must still end after a re-arm. `stopSelf(startId)` is
     * a no-op once a newer start has landed, and a re-arm IS a newer start, so
     * the timer armed by the original bounded start is orphaned: without a
     * rebind, "Weigh now" runs a BALANCED foreground scan forever.
     *
     * Asserted as "the rebound stop carries the re-arm's own startId", not as
     * "the service stopped" — Robolectric's ShadowService records
     * `stopSelf(int)` but does not model the platform's newer-start no-op, so
     * an isStoppedBySelf assertion would pass either way (prior learning
     * `robolectric-stopself-startid-not-modeled`).
     */
    @Test
    fun aRearmRebindsTheBoundedStopToItsOwnStartId() {
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.activeAddressProvider = { null }
        var now = 1_000L
        service.elapsedClock = { now }
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        service.boundStopScheduler = { millis, onExpire -> scheduled += millis to onExpire }

        service.onStartCommand(Intent().putExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, 120_000L), 0, 1)
        now += 30_000L
        service.onStartCommand(Intent().putExtra(BridgeForegroundService.EXTRA_REARM_SCAN, true), 0, 7)

        assertEquals("the re-arm must rebind, not merely leave the stale timer", 2, scheduled.size)
        assertEquals("only the time left in the window, not a fresh 120s", 90_000L, scheduled[1].first)

        scheduled[1].second()
        assertEquals(
            "the rebound stop must carry the newest startId or it is a no-op",
            7,
            shadowOf(service).stopSelfId,
        )
    }

    /** A window that already elapsed stops at once rather than scheduling zero. */
    @Test
    fun aRearmAfterTheBoundedWindowElapsedStopsImmediately() {
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.activeAddressProvider = { null }
        var now = 1_000L
        service.elapsedClock = { now }
        val scheduled = mutableListOf<Long>()
        service.boundStopScheduler = { millis, _ -> scheduled += millis }

        service.onStartCommand(Intent().putExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, 120_000L), 0, 1)
        now += 200_000L
        service.onStartCommand(Intent().putExtra(BridgeForegroundService.EXTRA_REARM_SCAN, true), 0, 7)

        assertEquals("no second timer for an expired window", 1, scheduled.size)
        assertEquals(7, shadowOf(service).stopSelfId)
    }

    /**
     * The clearest single proof the owner model works, and the one test whose
     * *meaning* the refactor changed.
     *
     * Before owners, a plain always-on start cleared the bounded deadline so
     * that no stop could be rebound — the window silently stopped existing,
     * because the only way to protect the always-on scan was to forget the
     * window. Now both claims are held: the window's release still fires on
     * schedule, and the service keeps running because `ALWAYS_ON` has not let
     * go. Interleaving stops being a special case.
     *
     * Asserted as "not stopped", which is safe in this direction: the concern
     * with `ShadowService` is that it records `stopSelf(int)` without modelling
     * the platform's newer-start no-op, so a *stopped* assertion can pass
     * vacuously. Nothing calling `stopSelf` at all is directly observable.
     */
    @Test
    fun aWeighNowWindowEndingUnderAlwaysOnReleasesTheWindowWithoutStoppingTheService() {
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        // A real address, unlike the other cases here: the re-arm runs
        // `startActiveScan`, and with nothing to scan for that stops the service
        // by a route that has nothing to do with ownership — which would make
        // the assertion below pass for the wrong reason.
        service.activeAddressProvider = { DEVICE_ADDRESS }
        var now = 1_000L
        service.elapsedClock = { now }
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        service.boundStopScheduler = { millis, onExpire -> scheduled += millis to onExpire }

        service.onStartCommand(Intent().putExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, 120_000L), 0, 1)
        service.onStartCommand(Intent(), 0, 2)
        now += 30_000L
        service.onStartCommand(Intent().putExtra(BridgeForegroundService.EXTRA_REARM_SCAN, true), 0, 3)

        assertEquals("the window still has to end, so its stop is rebound", 2, scheduled.size)
        assertEquals("only the time left in the window", 90_000L, scheduled[1].first)

        scheduled[1].second()

        assertEquals("the window released its own claim", setOf(BridgeOwner.ALWAYS_ON), service.owners)
        assertFalse(
            "always-on still wants the scan, so the window ending must not stop the service",
            shadowOf(service).isStoppedBySelf,
        )
    }

    /**
     * The other direction of the same invariant: with nobody else holding the
     * bridge, the window ending is the last release and the service does stop.
     */
    @Test
    fun aWeighNowWindowEndingAloneStopsTheService() {
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.activeAddressProvider = { null }
        service.elapsedClock = { 1_000L }
        var expire: (() -> Unit)? = null
        service.boundStopScheduler = { _, onExpire -> expire = onExpire }

        service.onStartCommand(Intent().putExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, 120_000L), 0, 4)
        expire?.invoke()

        assertTrue("the last owner letting go must stop the service", service.owners.isEmpty())
        assertEquals("and it must carry the newest start id", 4, shadowOf(service).stopSelfId)
    }

    /**
     * Releasing a claim nobody holds must not stop the service. The always-on
     * toggle can be switched off twice, and "Weigh now" can be cancelled after
     * its window already expired; stopping on either would `stopSelf` a service
     * a concurrent acquire had legitimately just started — the same race the
     * owner model exists to remove.
     */
    @Test
    fun releasingAnOwnerThatIsNotHeldDoesNotStopTheService() {
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.activeAddressProvider = { null }
        service.boundStopScheduler = { _, _ -> }

        service.onStartCommand(Intent(), 0, 1)
        service.releaseOwner(BridgeOwner.WEIGH_NOW)

        assertEquals(setOf(BridgeOwner.ALWAYS_ON), service.owners)
        assertFalse("releasing an unheld claim is a no-op", shadowOf(service).isStoppedBySelf)
    }

    /** Redelivery is real: `onStartCommand` can run twice for one logical start. */
    @Test
    fun acquiringAnOwnerTwiceIsIdempotent() {
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.activeAddressProvider = { null }
        service.boundStopScheduler = { _, _ -> }

        service.onStartCommand(Intent(), 0, 1)
        service.onStartCommand(Intent(), 0, 2)
        service.releaseOwner(BridgeOwner.ALWAYS_ON)

        assertTrue("a second acquire must not need a second release", service.owners.isEmpty())
    }

    /**
     * A sticky restart delivers a null `Intent`. `START_STICKY` is only ever
     * returned while `ALWAYS_ON` is held, so a null `Intent` is that owner
     * asking for its scan back — and reconstructing it this way needs no
     * `ConfigStore` read from `onStartCommand`. `WEIGH_NOW` is deliberately not
     * restored.
     */
    @Test
    fun aStickyRestartWithNoIntentRestoresOnlyTheAlwaysOnOwner() {
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.activeAddressProvider = { null }
        service.boundStopScheduler = { _, _ -> }

        val mode = service.onStartCommand(null, 0, 1)

        assertEquals(setOf(BridgeOwner.ALWAYS_ON), service.owners)
        assertEquals(Service.START_STICKY, mode)
    }

    @Test
    fun aScanResultIsDispatchedThroughTheInjectedEnqueuer() {
        val enqueuer = FakeScaleSessionEnqueuer()
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.enqueuerFactory = { enqueuer }

        service.enqueueOnce(scanResult(DEVICE_ADDRESS))

        assertEquals(DEVICE_ADDRESS, enqueuer.calls.single().address)
    }

    /**
     * The cooldown is what keeps a scale advertising several times a second
     * from starting a fresh GATT session per advertisement; with the enqueuer
     * injectable, that gate is finally observable at the service level.
     */
    @Test
    fun aRepeatAdvertisementInsideTheCooldownIsNotDispatchedAgain() {
        val enqueuer = FakeScaleSessionEnqueuer()
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.enqueuerFactory = { enqueuer }

        service.enqueueOnce(scanResult(DEVICE_ADDRESS))
        service.enqueueOnce(scanResult(DEVICE_ADDRESS))

        assertEquals(1, enqueuer.calls.size)
    }

    /**
     * pr-1-review-round3.md MEDIUM #16's other half: the window now lives in
     * SharedPreferences so both wake paths share it. Deliberate behavior
     * change for this service — a sighting the broadcast path already claimed
     * no longer starts a second session here.
     */
    @Test
    fun aSightingAlreadyClaimedByTheBroadcastPathIsNotDispatchedAgain() {
        val enqueuer = FakeScaleSessionEnqueuer()
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(ScanEnqueueCooldown(context).claim(DEVICE_ADDRESS))
        val service = Robolectric.buildService(BridgeForegroundService::class.java).get()
        service.enqueuerFactory = { enqueuer }

        service.enqueueOnce(scanResult(DEVICE_ADDRESS))

        assertTrue("both wake paths share one window per address", enqueuer.calls.isEmpty())
    }

    /**
     * pr-1-review-round3.md MEDIUM #15. `startActiveScan` used to return early
     * with no `stopSelf`, leaving a foreground service running forever behind
     * an undismissable "bridging" notification with no scan under it.
     */
    @Test
    fun noActiveProfileStopsTheServiceInsteadOfIdlingForeground() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { null }

        controller.create()

        assertTrue("a bridge with nothing to scan for must stop", shadowOf(controller.get()).isStoppedBySelf)
    }

    /**
     * The same inert state reached by the other route: a stored profile can
     * carry a malformed address, which `setDeviceAddress` rejects, and a
     * permission revoked mid-flight (E13) throws `SecurityException` out of
     * `startScan`. The bare `runCatching` swallowed both.
     */
    @Test
    fun aScanThatCannotBeStartedStopsTheServiceInsteadOfIdlingForeground() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { "not-a-mac-address" }

        controller.create()

        assertTrue("a bridge whose scan never started must stop", shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun aScanThatStartsLeavesTheServiceRunningInForeground() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { DEVICE_ADDRESS }

        controller.create()

        val shadow = shadowOf(controller.get())
        assertNotNull("the bridge notification is owed once a scan is running", shadow.lastForegroundNotification)
        assertFalse("a running scan must not stop the service", shadow.isStoppedBySelf)
    }

    /**
     * S4 continued: `weighNow()`'s bounded scan reuses this same service, so a
     * start carrying the duration extra must arm a self-stop — otherwise the
     * only difference between "weigh now" and "always-on" would be a duration
     * nobody enforces. [BridgeForegroundService.boundStopScheduler] is
     * injectable for the same reason [enqueuerFactory] and
     * [activeAddressProvider] are: real time cannot be waited out in a JVM test.
     */
    @Test
    fun aBoundedStartArmsASelfStopAfterItsWindow() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { DEVICE_ADDRESS }
        var scheduledMillis: Long? = null
        controller.get().boundStopScheduler = { millis, onExpire -> scheduledMillis = millis; onExpire() }
        controller.create()

        controller.get().onStartCommand(
            Intent().putExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, BOUND_MILLIS),
            0,
            1,
        )

        assertEquals(BOUND_MILLIS, scheduledMillis)
        assertTrue("the bounded window elapsing must stop the service", shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun aPlainStartNeverArmsASelfStop() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { DEVICE_ADDRESS }
        var scheduled = false
        controller.get().boundStopScheduler = { _, _ -> scheduled = true }
        controller.create()

        controller.get().onStartCommand(Intent(), 0, 1)

        assertFalse("the always-on toggle's start must not carry a self-stop timer", scheduled)
        assertFalse("no scheduled stop means the service is still running", shadowOf(controller.get()).isStoppedBySelf)
    }

    /**
     * H-1 (post-devil's-advocate review): the no-arg `stopSelf()` stops the
     * service unconditionally, ignoring any start command that arrived after
     * the one that armed the timer — so a `weighNow()` window expiring after
     * the user separately turned "Always-on" on kills the scan they just
     * asked for, with the toggle still reading on. `stopSelf(startId)` is a
     * documented no-op once a newer start has landed; this only proves this
     * service passes its OWN startId through, not that the platform actually
     * honors it — Robolectric's `ShadowService` records the call, it doesn't
     * model the real no-op-on-a-newer-start semantics.
     */
    @Test
    fun aBoundedStopIsArmedWithTheStartIdItWasGivenNotTheNoArgOverload() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { DEVICE_ADDRESS }
        controller.get().boundStopScheduler = { _, onExpire -> onExpire() }
        controller.create()

        controller.get().onStartCommand(
            Intent().putExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, BOUND_MILLIS),
            0,
            SPECIFIC_START_ID,
        )

        assertEquals(SPECIFIC_START_ID, shadowOf(controller.get()).stopSelfId)
    }

    /**
     * M-5 (post-devil's-advocate review): `START_STICKY` restarts the
     * service after a process kill with a null `Intent` — which a bounded
     * start would read as `boundMillis = 0`, arming no timer and leaving a
     * scan nothing will ever stop. `START_NOT_STICKY` for a bounded start
     * accepts losing the window on a rare mid-window process kill rather
     * than risking a permanently unbounded one.
     */
    @Test
    fun aBoundedStartReturnsNotStickySoAKilledProcessDoesNotRestartUnbounded() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { DEVICE_ADDRESS }
        controller.get().boundStopScheduler = { _, _ -> }
        controller.create()

        val result = controller.get().onStartCommand(
            Intent().putExtra(BridgeForegroundService.EXTRA_BOUND_MILLIS, BOUND_MILLIS),
            0,
            1,
        )

        assertEquals(Service.START_NOT_STICKY, result)
    }

    @Test
    fun aPlainStartReturnsStickyUnchangedFromBeforeThisOverrideExisted() {
        val controller = grantedController()
        controller.get().activeAddressProvider = { DEVICE_ADDRESS }
        controller.create()

        val result = controller.get().onStartCommand(Intent(), 0, 1)

        assertEquals(Service.START_STICKY, result)
    }

    private fun grantedController(): ServiceController<BridgeForegroundService> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_SCAN)
        return Robolectric.buildService(BridgeForegroundService::class.java)
    }

    @Suppress("DEPRECATION")
    private fun scanResult(address: String): ScanResult {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = requireNotNull(adapter?.getRemoteDevice(address)) { "no shadow BluetoothAdapter" }
        return ScanResult(device, null, RSSI, System.nanoTime())
    }

    private companion object {
        const val DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF"
        const val RSSI = -50
        const val BOUND_MILLIS = 120_000L
        const val SPECIFIC_START_ID = 7
    }
}
