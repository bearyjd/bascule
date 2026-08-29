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
import com.ventouxlabs.bascule.delivery.DeliveryTrigger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ManualEntryUiState(
    val weightText: String = "",
    val unit: WeightUnit = WeightUnit.KILOGRAMS,
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
)

/**
 * WP-24: a deliberate bypass of the BLE path entirely, for PWA parity. Every
 * saved row is `source = MANUAL`, `PENDING`, with every body-composition
 * field null — a manual entry is attributed by construction, so it skips
 * `§7`'s attribution gate the way a scale reading never can.
 */
class ManualEntryViewModel(
    private val dao: ReadingDao,
    configStore: ConfigStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val deliveryTrigger: DeliveryTrigger? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManualEntryUiState())
    val uiState: StateFlow<ManualEntryUiState> = _uiState.asStateFlow()

    /**
     * A one-shot event, not a sticky state field — [ManualEntryUiState] used
     * to carry `saved: Boolean`, which stays `true` once set. With the
     * bottom-nav's `saveState`/`restoreState` retaining this ViewModel across
     * tab switches, that would re-fire the screen's `onSaved` callback (and
     * pop the back stack) on every return to this tab, not just the save
     * that actually set it.
     */
    private val _savedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val savedEvents: SharedFlow<Unit> = _savedEvents

    init {
        // Collected continuously, not read once via .first() — a unit change
        // in Config while this screen is retained (tab switch, not a fresh
        // navigation) must not leave the label and the kg conversion using a
        // unit the user no longer has selected.
        viewModelScope.launch {
            configStore.displayUnit.collect { unit ->
                _uiState.value = _uiState.value.copy(unit = unit)
            }
        }
    }

    fun onWeightTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(weightText = text, errorMessage = null)
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return // two taps before the first insert completes must not double-insert

        // toDoubleOrNull() accepts "NaN" and "Infinity" — both compare false
        // against every bound below, so isFinite() must gate first or a NaN
        // weight sails through validation and persists a row that can never
        // dedup (every §3.3 comparison against NaN is false) or serialize.
        val parsed = state.weightText.toDoubleOrNull()?.takeIf { it.isFinite() }
        if (parsed == null) {
            _uiState.value = state.copy(errorMessage = "Enter a number")
            return
        }
        if (parsed < minPlausibleForUnit(state.unit) || parsed > maxPlausibleForUnit(state.unit)) {
            _uiState.value = state.copy(errorMessage = "That doesn't look like a plausible weight")
            return
        }

        val weightKg = state.unit.toKilograms(parsed)
        val now = nowMillis()
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

        _uiState.value = state.copy(isSaving = true)
        viewModelScope.launch {
            dao.insert(reading)
            deliveryTrigger?.triggerImmediateDrain()
            // The live unit, not the `state.unit` captured before the insert:
            // the `init` block collects `configStore.displayUnit` continuously
            // so a unit change while this screen is retained takes effect
            // immediately, and a reset using the stale pre-save value would
            // silently revert that change the moment this save completes.
            _uiState.value = ManualEntryUiState(unit = _uiState.value.unit)
            _savedEvents.emit(Unit)
        }
    }

    private fun minPlausibleForUnit(unit: WeightUnit): Double =
        unit.fromKilograms(WeightUnit.MIN_PLAUSIBLE_WEIGHT_KG)

    private fun maxPlausibleForUnit(unit: WeightUnit): Double =
        unit.fromKilograms(WeightUnit.MAX_PLAUSIBLE_WEIGHT_KG)

    companion object {
        fun factory(app: BasculeApplication) = viewModelFactory {
            initializer {
                ManualEntryViewModel(
                    app.database.readingDao(),
                    app.configStore,
                    deliveryTrigger = app.deliveryTrigger,
                )
            }
        }
    }
}
