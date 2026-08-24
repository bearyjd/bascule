package com.ventouxlabs.bascule

import android.app.Application
import com.ventouxlabs.bascule.ble.AndroidScaleRegistrar
import com.ventouxlabs.bascule.ble.ScaleRegistrar
import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.ble.session.EncryptedConsentStore
import com.ventouxlabs.bascule.data.BasculeDatabase
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.DataStoreConfigStore
import com.ventouxlabs.bascule.delivery.DeliveryTrigger
import com.ventouxlabs.bascule.delivery.WorkManagerDeliveryTrigger
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounters
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import com.ventouxlabs.bascule.network.AuthTokenStore
import com.ventouxlabs.bascule.network.EncryptedAuthTokenStore
import com.ventouxlabs.bascule.network.EncryptedSessionCookieStore
import com.ventouxlabs.bascule.network.SessionCookieStore

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

    val database: BasculeDatabase by lazy { BasculeDatabase.getInstance(this) }
    val authTokenStore: AuthTokenStore by lazy { EncryptedAuthTokenStore(this) }
    val sessionCookieStore: SessionCookieStore by lazy { EncryptedSessionCookieStore(this) }
    val consentStore: ConsentStore by lazy { EncryptedConsentStore(this) }
    val configStore: ConfigStore by lazy { DataStoreConfigStore(this) }
    val deliveryTrigger: DeliveryTrigger by lazy { WorkManagerDeliveryTrigger(this) }
    val scaleRegistrar: ScaleRegistrar by lazy {
        AndroidScaleRegistrar(this, consentStore, configStore, diagnosticsCounters)
    }

    /**
     * Process-lifetime only until WP-26 provides a persistent implementation
     * behind the same interface (see [InMemoryDiagnosticsCounters]'s own
     * KDoc) — a single shared instance here so a future WP-08 session worker
     * and the UI observe the same counts, not independent copies.
     */
    val diagnosticsCounters: DiagnosticsCounters by lazy { InMemoryDiagnosticsCounters() }
}
