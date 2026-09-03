package com.ventouxlabs.bascule.ui.fake

import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.WeightUnit
import com.ventouxlabs.bascule.network.ContractVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [ConfigStore] for JVM tests — no DataStore, no instrumented test needed. */
class FakeConfigStore(
    initialBaseUrl: String? = null,
    initialDisplayUnit: WeightUnit = WeightUnit.KILOGRAMS,
    initialContractVersion: ContractVersion = ContractVersion.V1_WEIGHT_ONLY,
    initialAlwaysOnBridging: Boolean = false,
    initialPairedDeviceAddress: String? = null,
) : ConfigStore {

    private val _baseUrl = MutableStateFlow(initialBaseUrl)
    override val baseUrl: StateFlow<String?> = _baseUrl.asStateFlow()

    private val _displayUnit = MutableStateFlow(initialDisplayUnit)
    override val displayUnit: StateFlow<WeightUnit> = _displayUnit.asStateFlow()

    private val _contractVersion = MutableStateFlow(initialContractVersion)
    override val contractVersion: StateFlow<ContractVersion> = _contractVersion.asStateFlow()

    private val _alwaysOnBridging = MutableStateFlow(initialAlwaysOnBridging)
    override val alwaysOnBridging: StateFlow<Boolean> = _alwaysOnBridging.asStateFlow()

    private val _automaticCaptureEnabled = MutableStateFlow(false)
    override val automaticCaptureEnabled: StateFlow<Boolean> = _automaticCaptureEnabled.asStateFlow()

    private val _pairedDeviceAddress = MutableStateFlow(initialPairedDeviceAddress)
    override val pairedDeviceAddress: StateFlow<String?> = _pairedDeviceAddress.asStateFlow()

    private val _lastReplayMigrationContractVersion = MutableStateFlow<ContractVersion?>(null)
    override val lastReplayMigrationContractVersion: StateFlow<ContractVersion?> =
        _lastReplayMigrationContractVersion.asStateFlow()

    override suspend fun saveBaseUrl(url: String) {
        _baseUrl.value = url
    }

    override suspend fun saveDisplayUnit(unit: WeightUnit) {
        _displayUnit.value = unit
    }

    override suspend fun saveContractVersion(version: ContractVersion) {
        _contractVersion.value = version
    }

    override suspend fun saveAlwaysOnBridging(enabled: Boolean) {
        _alwaysOnBridging.value = enabled
    }

    override suspend fun saveAutomaticCaptureEnabled(enabled: Boolean) {
        _automaticCaptureEnabled.value = enabled
    }

    override suspend fun savePairedDeviceAddress(address: String?) {
        _pairedDeviceAddress.value = address
    }

    override suspend fun saveLastReplayMigrationContractVersion(version: ContractVersion) {
        _lastReplayMigrationContractVersion.value = version
    }
}
