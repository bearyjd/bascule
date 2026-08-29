package com.ventouxlabs.bascule.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.ventouxlabs.bascule.ble.decoders.BeurerDecoder
import com.ventouxlabs.bascule.ble.decoders.SigWeightProfile
import com.ventouxlabs.bascule.ble.session.AndroidGattTransport
import com.ventouxlabs.bascule.ble.session.ConsentStore
import com.ventouxlabs.bascule.ble.session.ScaleCredential
import com.ventouxlabs.bascule.ble.session.GattSession
import com.ventouxlabs.bascule.ble.session.SessionOutcome
import com.ventouxlabs.bascule.ble.session.ScaleOperationCoordinator
import com.ventouxlabs.bascule.ble.session.ScaleSessionPurpose
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.diagnostics.DiagnosticsCounters
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

enum class RegistrationPhase { SCANNING, CONNECTING }

sealed interface ScaleRegistrationResult {
    data class Success(val address: String, val scaleIndex: Int) : ScaleRegistrationResult
    data class Failure(val message: String) : ScaleRegistrationResult
}

interface ScaleRegistrar {
    suspend fun register(forceNew: Boolean, onPhase: (RegistrationPhase) -> Unit): ScaleRegistrationResult
}

/** Foreground, user-initiated registration path used by ConfigScreen. */
class AndroidScaleRegistrar(
    context: Context,
    private val consentStore: ConsentStore,
    private val configStore: ConfigStore,
    private val diagnostics: DiagnosticsCounters,
    private val coordinator: ScaleOperationCoordinator,
) : ScaleRegistrar {

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    @SuppressLint("MissingPermission")
    override suspend fun register(
        forceNew: Boolean,
        onPhase: (RegistrationPhase) -> Unit,
    ): ScaleRegistrationResult {
        val adapter = bluetoothManager?.adapter
            ?: return ScaleRegistrationResult.Failure("Bluetooth is not available on this device")
        if (!adapter.isEnabled) return ScaleRegistrationResult.Failure("Turn on Bluetooth and try again")

        onPhase(RegistrationPhase.SCANNING)
        val scan = try {
            findScale()
        } catch (_: SecurityException) {
            return ScaleRegistrationResult.Failure("Bluetooth permission is required to find the scale")
        }
        val device = scan ?: return ScaleRegistrationResult.Failure(
            "No BF720 found. Wake the scale, stay nearby, and try again.",
        )

        onPhase(RegistrationPhase.CONNECTING)
        return registerDevice(device, adapter, forceNew)
    }

    private suspend fun registerDevice(
        device: ScanResult,
        adapter: android.bluetooth.BluetoothAdapter,
        forceNew: Boolean,
    ): ScaleRegistrationResult {
        val address = device.device.address
        val forcedNewRegistration = if (forceNew) ForceNewRegistrationConsentStore(consentStore) else null
        val sessionConsentStore = forcedNewRegistration ?: consentStore
        val session = GattSession(
            transport = AndroidGattTransport(appContext, device.device, adapter),
            decoder = BeurerDecoder(),
            consentStore = sessionConsentStore,
            deviceAddress = address,
            diagnostics = diagnostics,
            purpose = ScaleSessionPurpose.REGISTER_NEW,
            stopAfterHandshake = true,
        )
        val outcome = try {
            coordinator.withScale(ScaleSessionPurpose.REGISTER_NEW) { session.run() }
        } catch (_: SecurityException) {
            return ScaleRegistrationResult.Failure("Bluetooth permission was revoked during registration")
        }
        val credential = registrationCredential(
            forceNew = forceNew,
            savedThisSession = forcedNewRegistration?.savedCredential,
            existing = consentStore.credentialFor(address),
        )
        if (outcome is SessionOutcome.Completed && credential != null) {
            configStore.savePairedDeviceAddress(address)
            return ScaleRegistrationResult.Success(address, credential.scaleIndex)
        }
        return ScaleRegistrationResult.Failure(
            when (outcome) {
                is SessionOutcome.HandshakeFailed -> outcome.detail
                SessionOutcome.Incompatible -> "The discovered device is not a compatible BF720"
                is SessionOutcome.Missed -> "Registration did not complete (${outcome.reason.name.lowercase()})"
                is SessionOutcome.DecodeFailure -> "The scale returned an unreadable registration response"
                // Reachable only with credential == null: the guard above
                // already returned Success for a Completed session that did
                // produce a slot. Completed.reading is irrelevant here —
                // registration runs with stopAfterHandshake, so it is always
                // null, and the slot is what this path is waiting on.
                is SessionOutcome.Completed -> "Registration finished without a user slot"
            },
        )
    }

    /**
     * Hides any stored credential from the session so the decoder registers a
     * fresh user slot instead of consenting with the old one, while recording
     * what the scale assigned. Named rather than an anonymous object so the
     * "forget the old credential but remember the new one" rule can be read —
     * and tested — on its own.
     */
    private class ForceNewRegistrationConsentStore(private val delegate: ConsentStore) : ConsentStore {
        var savedCredential: ScaleCredential? = null
            private set

        override fun credentialFor(deviceAddress: String): ScaleCredential? = null

        override fun save(deviceAddress: String, credential: ScaleCredential) {
            savedCredential = credential
            delegate.save(deviceAddress, credential)
        }

        override fun clear(deviceAddress: String) = Unit

        override fun newConsentCode(): Int = delegate.newConsentCode()
    }

    @SuppressLint("MissingPermission")
    private suspend fun findScale(): ScanResult? {
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: return null
        return withTimeoutOrNull(SCAN_TIMEOUT) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        scanner.stopScan(this)
                        if (continuation.isActive) continuation.resume(result)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        scanner.stopScan(this)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation { scanner.stopScan(callback) }
                val filter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(SigWeightProfile.WEIGHT_SCALE_SERVICE))
                    .build()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(listOf(filter), settings, callback)
            }
        }
    }

    private companion object {
        val SCAN_TIMEOUT = 20.seconds
    }
}

/**
 * The credential that decides a registration session's success, and the only
 * part of that decision worth a name: `consentStore.credentialFor` matches on
 * address alone, so [existing] is returned whether or not *this* session got
 * anywhere. That's correct for `forceNew = false` — recovering a mapping this
 * session didn't need to touch — but wrong for `forceNew = true`: a session
 * that completes without registering (`BeurerDecoder`'s `Complete` carries a
 * null credential whenever `registered` is false) must not report success
 * carrying the *old* slot the fresh registration was supposed to replace.
 * Only [savedThisSession] — what the `ForceNewRegistrationConsentStore`
 * wrapper actually recorded — may answer for a forced re-registration.
 *
 * A pure top-level function rather than inline logic so this rule is
 * unit-testable without a live GATT connection: `registerDevice` needs a real
 * `ScanResult` and `AndroidGattTransport`, which the JVM test lane cannot
 * provide.
 */
internal fun registrationCredential(
    forceNew: Boolean,
    savedThisSession: ScaleCredential?,
    existing: ScaleCredential?,
): ScaleCredential? = if (forceNew) savedThisSession else existing
