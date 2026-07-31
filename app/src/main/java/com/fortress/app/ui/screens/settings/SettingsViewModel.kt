package com.fortress.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.app.data.model.AutopilotSettings
import com.fortress.app.data.preferences.AppPreferences
import com.fortress.app.data.repository.FortressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: FortressRepository = FortressRepository()
) : ViewModel() {

    enum class KeyStatus { IDLE, TESTING, VALID, INVALID }

    data class UiState(
        val settings: AutopilotSettings = AutopilotSettings(),
        val loading: Boolean = true,
        val draftKey: String = "",
        val savedKey: String = "",
        val keyStatus: KeyStatus = KeyStatus.IDLE,
        val keyVisible: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(context: Context) {
        viewModelScope.launch {
            val key = AppPreferences.geminiKeyFlow(context).firstOrNull().orEmpty()
            val settings = runCatching { repo.settings() }.getOrDefault(AutopilotSettings())
            _state.update { it.copy(loading = false, settings = settings, savedKey = key, draftKey = key) }
        }
    }

    private fun persist(newSettings: AutopilotSettings) {
        _state.update { it.copy(settings = newSettings) }
        viewModelScope.launch { runCatching { repo.updateSettings(newSettings) } }
    }

    fun setKillSwitch(paused: Boolean) = persist(_state.value.settings.copy(killSwitch = paused))
    fun setAllocatedCapital(v: Double) = persist(_state.value.settings.copy(allocatedCapital = v))
    fun setMaxPositionPct(v: Double) = persist(_state.value.settings.copy(maxPositionPct = v))
    fun setDriftThreshold(v: Double) = persist(_state.value.settings.copy(driftThresholdPct = v))
    fun setCadence(v: String) = persist(_state.value.settings.copy(rebalanceCadence = v))

    // ── Gemini key ──────────────────────────────────────────────────────────────
    fun setDraft(key: String) = _state.update { it.copy(draftKey = key, keyStatus = KeyStatus.IDLE) }
    fun toggleVisibility() = _state.update { it.copy(keyVisible = !it.keyVisible) }

    fun saveAndTest(context: Context) {
        val key = _state.value.draftKey.trim()
        if (key.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(keyStatus = KeyStatus.TESTING) }
            AppPreferences.saveGeminiKey(context, key)
            val valid = repo.testGeminiKey(key)
            _state.update {
                it.copy(savedKey = key, keyStatus = if (valid) KeyStatus.VALID else KeyStatus.INVALID)
            }
        }
    }

    fun clearKey(context: Context) {
        viewModelScope.launch {
            AppPreferences.clearGeminiKey(context)
            _state.update { it.copy(savedKey = "", draftKey = "", keyStatus = KeyStatus.IDLE) }
        }
    }
}
