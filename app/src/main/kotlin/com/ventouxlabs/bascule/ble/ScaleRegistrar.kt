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
        var newlySavedCredential: com.ventouxlabs.bascule.ble.session.ScaleCredential? = null
        val sessionConsentStore = if (!forceNew) consentStore else object : ConsentStore {
            override fun credentialFor(deviceAddress: String) = null
            override fun save(deviceAddress: String, credential: com.ventouxlabs.bascule.ble.session.ScaleCredential) {
                newlySavedCredential = credential
                consentStore.save(deviceAddress, credential)
            }
            override fun clear(deviceAddress: String) = Unit
            override fun newConsentCode(): Int = consentStore.newConsentCode()
        }
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
        val credential = newlySavedCredential ?: consentStore.credentialFor(address)
        if (credential != null) {
            configStore.savePairedDeviceAddress(address)
            return ScaleRegistrationResult.Success(address, credential.scaleIndex)
        }
        return ScaleRegistrationResult.Failure(
            when (outcome) {
                is SessionOutcome.HandshakeFailed -> outcome.detail
                SessionOutcome.Incompatible -> "The discovered device is not a compatible BF720"
                is SessionOutcome.Missed -> "Registration did not complete (${outcome.reason.name.lowercase()})"
                is SessionOutcome.DecodeFailure -> "The scale returned an unreadable registration response"
                is SessionOutcome.Completed -> "Registration finished without a user slot"
            },
        )
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
