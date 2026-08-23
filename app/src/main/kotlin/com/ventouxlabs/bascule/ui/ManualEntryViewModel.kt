package com.ventouxlabs.bascule.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ReadingEntity
import com.ventouxlabs.bascule.data.ReadingSource
import com.ventouxlabs.bascule.data.ReadingStatus
import com.ventouxlabs.bascule.data.WeightUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

data class ManualEntryUiState(
    val weightText: String = "",
    val unit: WeightUnit = WeightUnit.KILOGRAMS,
    val errorMessage: String? = null,
    val saved: Boolean = false,
)

/**
 * WP-24: a deliberate bypass of the BLE path entirely, for PWA parity. Every
 * saved row is `source = MANUAL`, `PENDING`, with every body-composition
 * field null — a manual entry is attributed by construction, so it skips
 * `§7`'s attribution gate the way a scale reading never can.
 */
class ManualEntryViewModel(
    private val dao: ReadingDao,
    private val configStore: ConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManualEntryUiState())
    val uiState: StateFlow<ManualEntryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(unit = configStore.displayUnit.first())
        }
    }

    fun onWeightTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(weightText = text, errorMessage = null)
    }

    fun save() {
        val state = _uiState.value
        val parsed = state.weightText.toDoubleOrNull()
        if (parsed == null) {
            _uiState.value = state.copy(errorMessage = "Enter a number")
            return
        }
        if (parsed < minPlausibleForUnit(state.unit) || parsed > maxPlausibleForUnit(state.unit)) {
            _uiState.value = state.copy(errorMessage = "That doesn't look like a plausible weight")
            return
        }

        val weightKg = state.unit.toKilograms(parsed)
        val now = System.currentTimeMillis()
        val reading = ReadingEntity(
            id = UUID.randomUUID().toString(),
            capturedAtMillis = now,
            scaleTimestampMillis = null,
            userIndex = null,
            weightKg = weightKg,
            displayUnit = state.unit.wire,
            bodyFatPct = null,
            bodyWaterPct = null,
            musclePct = null,
            boneMassKg = null,
            bmi = null,
            bmr = null,
            amr = null,
            impedanceOhms = null,
            softLeanMassKg = null,
            status = ReadingStatus.PENDING,
            attemptCount = 0,
            retryEpochMillis = now,
            lastAttemptMillis = null,
            lastError = null,
            lastErrorClass = null,
            deliveredFields = emptySet(),
            contractVersionAtDelivery = null,
            remoteDuplicate = false,
            source = ReadingSource.MANUAL,
        )

        viewModelScope.launch {
            dao.insert(reading)
            _uiState.value = ManualEntryUiState(unit = state.unit, saved = true)
        }
    }

    private fun minPlausibleForUnit(unit: WeightUnit): Double = unit.fromKilograms(MIN_PLAUSIBLE_WEIGHT_KG)
    private fun maxPlausibleForUnit(unit: WeightUnit): Double = unit.fromKilograms(MAX_PLAUSIBLE_WEIGHT_KG)

    companion object {
        // A bathroom scale's plausible human range, generous on both ends
        // rather than tuned to any one body type. Kilograms is the boundary
        // that matters for storage; per-unit bounds are derived from it so
        // both display units reject the same physical range, not the same
        // raw number.
        const val MIN_PLAUSIBLE_WEIGHT_KG = 20.0
        const val MAX_PLAUSIBLE_WEIGHT_KG = 300.0

        fun factory(app: BasculeApplication) = viewModelFactory {
            initializer { ManualEntryViewModel(app.database.readingDao(), app.configStore) }
        }
    }
}
