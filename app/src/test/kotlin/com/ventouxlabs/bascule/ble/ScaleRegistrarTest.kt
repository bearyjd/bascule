package com.ventouxlabs.bascule.ble

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import androidx.test.core.app.ApplicationProvider
import com.ventouxlabs.bascule.ble.fake.InMemoryConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.ble.session.ScaleOperationCoordinator
import com.ventouxlabs.bascule.diagnostics.InMemoryDiagnosticsCounters
import com.ventouxlabs.bascule.ui.fake.FakeConfigStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * C3: [AndroidScaleRegistrar]'s pre-flight refusals — the two cases that are
 * decided before any scan or GATT connection is attempted, and so are the only
 * ones reachable from the JVM lane.
 *
 * Everything past `findScale()` is not: `registerDevice` needs a real
 * `ScanResult` plus a live `AndroidGattTransport`, and the pieces a test would
 * need to substitute for them are not seams —
 * `ForceNewRegistrationConsentStore` is a `private` nested class and the
 * [com.ventouxlabs.bascule.ble.session.SessionOutcome]-to-message mapping is an
 * expression inside `registerDevice`. So the five-way failure-message mapping,
 * both `SecurityException` catches, and the `forceNew` decorator stay uncovered
 * here; see this wave's report for the seams a fix would need to expose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScaleRegistrarTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val configStore = FakeConfigStore()

    private fun registrar(consentStore: InMemoryConsentStore = InMemoryConsentStore()) = AndroidScaleRegistrar(
        context = context,
        consentStore = consentStore,
        configStore = configStore,
        diagnostics = InMemoryDiagnosticsCounters(),
        coordinator = ScaleOperationCoordinator(),
    )

    private fun adapter(): BluetoothAdapter =
        requireNotNull(context.getSystemService(BluetoothManager::class.java)).adapter

    @Test
    fun registrationRefusesUpFrontWhenBluetoothIsTurnedOff() = runTest {
        shadowOf(adapter()).setEnabled(false)
        val phases = mutableListOf<RegistrationPhase>()

        val result = registrar().register(forceNew = false) { phases += it }

        assertEquals(
            ScaleRegistrationResult.Failure("Turn on Bluetooth and try again"),
            result,
        )
        assertEquals(
            "refusing before SCANNING keeps the screen from showing a scan that never started",
            emptyList<RegistrationPhase>(),
            phases,
        )
    }

    /**
     * A regression guard against future reordering, not coverage of a branch
     * that can currently fail: today `register` returns at the `isEnabled`
     * check before reaching anything that writes. It exists so that moving a
     * `clear()` or a `savePairedDeviceAddress` above that check is caught — the
     * credential is only recoverable by physically re-registering with the
     * scale, which consumes one of its eight slots.
     */
    @Test
    fun aRefusedRegistrationTouchesNeitherTheCredentialNorThePairedAddress() = runTest {
        shadowOf(adapter()).setEnabled(false)
        val consentStore = InMemoryConsentStore()

        registrar(consentStore).register(forceNew = true) {}

        assertNull(consentStore.credentialFor("E7:DB:51:F1:36:91"))
        assertNull(
            "nothing may be marked paired on a registration that never reached a device",
            configStore.pairedDeviceAddress.value,
        )
    }

    /**
     * pr-1-review-quality (batch-4 review) MEDIUM-2: a forced re-registration
     * whose handshake completed without actually registering (`BeurerDecoder`'s
     * `Complete` carries a null credential whenever `registered` is false)
     * must not report success carrying the old slot the fresh registration was
     * supposed to replace. See [registrationCredential] — extracted because
     * `registerDevice` itself needs a live GATT connection this test lane
     * cannot provide.
     */
    @Test
    fun aForcedRegistrationThatDidNotActuallyRegisterIgnoresTheOldCredential() {
        val old = ScaleCredential(scaleIndex = 2, consentCode = 1234)

        assertNull(
            "nothing new was saved this session — the old slot must not be reported as success",
            registrationCredential(forceNew = true, savedThisSession = null, existing = old),
        )
    }

    @Test
    fun aForcedRegistrationThatDidRegisterUsesTheNewCredentialNotTheOld() {
        val old = ScaleCredential(scaleIndex = 2, consentCode = 1234)
        val new = ScaleCredential(scaleIndex = 5, consentCode = 5678)

        assertEquals(new, registrationCredential(forceNew = true, savedThisSession = new, existing = old))
    }

    @Test
    fun aNonForcedRegistrationFallsBackToWhateverIsAlreadyOnRecord() {
        val existing = ScaleCredential(scaleIndex = 2, consentCode = 1234)

        assertEquals(existing, registrationCredential(forceNew = false, savedThisSession = null, existing = existing))
    }
}
