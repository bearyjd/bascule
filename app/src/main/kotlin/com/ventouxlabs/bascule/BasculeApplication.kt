package com.ventouxlabs.bascule

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.ventouxlabs.bascule.ble.AndroidScaleRegistrar
import com.ventouxlabs.bascule.ble.ScaleRegistrar
import com.ventouxlabs.bascule.ble.ScaleScanner
import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.ble.session.EncryptedConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleOperationCoordinator
import com.ventouxlabs.bascule.data.BasculeDatabase
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.DataStoreConfigStore
import com.ventouxlabs.bascule.data.EncryptedScaleProfileStore
import com.ventouxlabs.bascule.data.ReadingIngestor
import com.ventouxlabs.bascule.data.ScaleProfileStore
import com.ventouxlabs.bascule.delivery.DeliveryTrigger
import com.ventouxlabs.bascule.delivery.DeliveryScheduler
import com.ventouxlabs.bascule.delivery.WorkManagerDeliveryScheduler
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounters
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import com.ventouxlabs.bascule.network.AuthTokenStore
import com.ventouxlabs.bascule.network.EncryptedAuthTokenStore
import com.ventouxlabs.bascule.network.EncryptedSessionCookieStore
import com.ventouxlabs.bascule.network.SessionCookieStore
import com.ventouxlabs.bascule.network.RuntimeApiFactory
import com.ventouxlabs.bascule.service.BridgeForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Composition root. No DI framework is in this project's dependency set
 * (`AndroidX + kotlinx only`), so this is a plain lazy service locator.
 * ViewModels take their dependencies through the constructor (per this
 * project's own testing conventions — fakes over mocks, plain-JUnit-testable
 * business logic) rather than reading this class directly; each ViewModel's
 * companion `factory` function is what actually reaches in here, once, at
 * the Compose call site.
 *
 * The background wake path (WP-08) is armed from [onCreate]:
 * [com.ventouxlabs.bascule.ble.ScaleScanner] registers a `PendingIntent` scan
 * that wakes [com.ventouxlabs.bascule.ble.ScanBroadcastReceiver], which in turn
 * enqueues [com.ventouxlabs.bascule.ble.session.ScaleSessionWorker].
 * [com.ventouxlabs.bascule.ble.ScaleScanner.arm] is itself gated on the
 * automatic-capture setting and a registered active profile, so this call is a
 * no-op until the user has opted in.
 */
class BasculeApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: BasculeDatabase by lazy { BasculeDatabase.getInstance(this) }
    val authTokenStore: AuthTokenStore by lazy { EncryptedAuthTokenStore(this) }
    val sessionCookieStore: SessionCookieStore by lazy { EncryptedSessionCookieStore(this) }
    private val legacyConsentStore: ConsentStore by lazy { EncryptedConsentStore(this) }
    val scaleProfileStore: ScaleProfileStore by lazy { EncryptedScaleProfileStore(this, legacyConsentStore) }
    val consentStore: ConsentStore get() = scaleProfileStore
    val configStore: ConfigStore by lazy { DataStoreConfigStore(this) }
    val deliveryScheduler: DeliveryScheduler by lazy { WorkManagerDeliveryScheduler(this) }
    val deliveryTrigger: DeliveryTrigger get() = deliveryScheduler
    val runtimeApiFactory: RuntimeApiFactory by lazy {
        RuntimeApiFactory(configStore, authTokenStore, sessionCookieStore)
    }
    val scaleOperationCoordinator by lazy { ScaleOperationCoordinator() }
    val readingIngestor by lazy {
        ReadingIngestor(
            database.readingDao(),
            scaleProfileStore,
            unitProvider = { configStore.displayUnit.first() },
        )
    }
    val scaleScanner by lazy { ScaleScanner(this, configStore, scaleProfileStore) }
    val scaleRegistrar: ScaleRegistrar by lazy {
        AndroidScaleRegistrar(this, consentStore, configStore, diagnosticsCounters, scaleOperationCoordinator)
    }

    /**
     * Process-lifetime only until WP-26 provides a persistent implementation
     * behind the same interface (see [InMemoryDiagnosticsCounters]'s own
     * KDoc) — a single shared instance here so a future WP-08 session worker
     * and the UI observe the same counts, not independent copies.
     */
    val diagnosticsCounters: DiagnosticsCounters by lazy { InMemoryDiagnosticsCounters() }

    private val _alwaysOnBridgingStartFailed = MutableStateFlow(false)

    /** True when [startBridgeService]'s last attempt was refused by the platform. */
    val alwaysOnBridgingStartFailed: StateFlow<Boolean> = _alwaysOnBridgingStartFailed.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        deliveryScheduler.ensurePeriodicDrain()
        applicationScope.launch {
            // Lazy, non-destructive migration of the existing BF720 slot mapping.
            configStore.pairedDeviceAddress.first()?.let(scaleProfileStore::migrateLegacyCredential)
            scaleScanner.arm()
            if (configStore.alwaysOnBridging.first()) startBridgeService()
        }
    }

    /**
     * Android 12+ throws `ForegroundServiceStartNotAllowedException` when the
     * process is not in a state permitted to start a foreground service — at
     * boot, exactly the state this call runs in. Caught as its
     * `IllegalStateException` supertype so no API-31 reference is needed, and
     * caught *narrowly*: the previous `runCatching` swallowed `Throwable`,
     * which in a coroutine also swallows `CancellationException`.
     *
     * The failure is only recorded, never rethrown — the app is still usable
     * without always-on bridging, and this codebase has no logging by design.
     * [alwaysOnBridgingStartFailed] is the surface a future ConfigScreen note
     * would read; nothing renders it yet, so a boot-time failure is still
     * invisible to the user.
     */
    private fun startBridgeService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, BridgeForegroundService::class.java),
            )
            _alwaysOnBridgingStartFailed.value = false
        } catch (_: IllegalStateException) {
            _alwaysOnBridgingStartFailed.value = true
        }
    }
}
