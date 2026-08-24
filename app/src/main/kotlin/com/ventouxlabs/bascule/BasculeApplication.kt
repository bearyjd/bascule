@file:Suppress("MaxLineLength")

package com.ventouxlabs.bascule

import android.app.Application
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
 * The background wake path (scanning, session handling — WP-08) is
 * deliberately not started here: [com.ventouxlabs.bascule.ble.ScaleScanner],
 * [com.ventouxlabs.bascule.ble.ScanBroadcastReceiver] and
 * [com.ventouxlabs.bascule.ble.session.ScaleSessionWorker] are unimplemented
 * stubs today, not merely unwired. Arming them belongs to WP-08 landing, not
 * to this UI work.
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
    val runtimeApiFactory: RuntimeApiFactory by lazy { RuntimeApiFactory(configStore, authTokenStore, sessionCookieStore) }
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

    override fun onCreate() {
        super.onCreate()
        deliveryScheduler.ensurePeriodicDrain()
        applicationScope.launch {
            // Lazy, non-destructive migration of the existing BF720 slot mapping.
            configStore.pairedDeviceAddress.first()?.let(scaleProfileStore::credentialFor)
            scaleScanner.arm()
            if (configStore.alwaysOnBridging.first()) {
                runCatching {
                    androidx.core.content.ContextCompat.startForegroundService(
                        this@BasculeApplication,
                        android.content.Intent(
                            this@BasculeApplication,
                            com.ventouxlabs.bascule.service.BridgeForegroundService::class.java,
                        ),
                    )
                }
            }
        }
    }
}
