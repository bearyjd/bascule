@file:Suppress("MaxLineLength", "MagicNumber")

package com.ventouxlabs.bascule.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ventouxlabs.bascule.BasculeApplication
import com.ventouxlabs.bascule.data.ConfigStore
import com.ventouxlabs.bascule.data.ReadingDao
import com.ventouxlabs.bascule.data.ScaleProfile
import com.ventouxlabs.bascule.data.ScaleProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScaleUiState(
    val profiles: List<ScaleProfile> = emptyList(),
    val activeProfileId: String? = null,
    val automaticCaptureEnabled: Boolean = false,
    val alwaysOnBridging: Boolean = false,
    val pendingDeliveries: Int = 0,
    val lastCaptureMillis: Long? = null,
    val diagnostic: String? = null,
)

class ScaleViewModel(
    private val config: ConfigStore,
    private val profiles: ScaleProfileStore,
    dao: ReadingDao,
    private val onArm: suspend () -> Boolean,
    private val onDisarm: () -> Unit,
    private val onBridgeChange: (Boolean) -> Unit,
) : ViewModel() {
    private val diagnostic = MutableStateFlow<String?>(null)
    val uiState: StateFlow<ScaleUiState> = combine(
        profiles.profiles,
        config.automaticCaptureEnabled,
        config.alwaysOnBridging,
        dao.observePendingCount(),
        dao.observeLastScaleCapture(),
        diagnostic,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val all = values[0] as List<ScaleProfile>
        ScaleUiState(
            profiles = all,
            activeProfileId = all.firstOrNull { it.active }?.id,
            automaticCaptureEnabled = values[1] as Boolean,
            alwaysOnBridging = values[2] as Boolean,
            pendingDeliveries = values[3] as Int,
            lastCaptureMillis = values[4] as Long?,
            diagnostic = values[5] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScaleUiState())

    fun setAutomaticCapture(enabled: Boolean) = viewModelScope.launch {
        if (enabled && profiles.activeProfile.value == null) {
            diagnostic.value = "Link or register a profile before enabling automatic capture."
            return@launch
        }
        config.saveAutomaticCaptureEnabled(enabled)
        if (enabled) {
            diagnostic.value = if (onArm()) null else "Background scan could not be armed. Check Bluetooth and permissions."
        } else {
            onDisarm()
            diagnostic.value = null
        }
    }

    fun setAlwaysOnBridging(enabled: Boolean) = viewModelScope.launch {
        config.saveAlwaysOnBridging(enabled)
        onBridgeChange(enabled)
    }

    fun setActive(profileId: String) = viewModelScope.launch {
        profiles.setActive(profileId)
        if (config.automaticCaptureEnabled.stateValue()) onArm()
    }

    fun rename(profile: ScaleProfile, label: String) {
        val safe = label.trim().take(40)
        if (safe.isEmpty()) return
        profiles.saveProfile(profile.copy(label = safe))
    }

    private suspend fun kotlinx.coroutines.flow.Flow<Boolean>.stateValue(): Boolean = first()

    companion object {
        fun factory(app: BasculeApplication) = viewModelFactory {
            initializer {
                ScaleViewModel(
                    app.configStore, app.scaleProfileStore, app.database.readingDao(),
                    onArm = app.scaleScanner::arm, onDisarm = app.scaleScanner::disarm,
                    onBridgeChange = { enabled ->
                        val intent = android.content.Intent(app, com.ventouxlabs.bascule.service.BridgeForegroundService::class.java)
                        if (enabled) androidx.core.content.ContextCompat.startForegroundService(app, intent)
                        else app.stopService(intent)
                    },
                )
            }
        }
    }
}
