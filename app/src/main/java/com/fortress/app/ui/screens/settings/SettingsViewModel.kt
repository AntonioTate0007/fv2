package com.fortress.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.app.data.preferences.AppPreferences
import com.fortress.app.data.repository.FortressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    enum class KeyStatus { IDLE, TESTING, VALID, INVALID }

    data class UiState(
        val draftKey: String = "",
        val savedKey: String = "",
        val keyStatus: KeyStatus = KeyStatus.IDLE,
        val keyVisible: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(context: Context) {
        viewModelScope.launch {
            AppPreferences.geminiKeyFlow(context).collect { key ->
                _state.update { it.copy(savedKey = key, draftKey = key) }
            }
        }
    }

    fun setDraft(key: String) = _state.update { it.copy(draftKey = key, keyStatus = KeyStatus.IDLE) }

    fun toggleVisibility() = _state.update { it.copy(keyVisible = !it.keyVisible) }

    fun saveAndTest(context: Context) {
        val key = _state.value.draftKey.trim()
        if (key.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(keyStatus = KeyStatus.TESTING) }
            AppPreferences.saveGeminiKey(context, key)
            val valid = FortressRepository(context).testGeminiKey(key)
            _state.update { it.copy(keyStatus = if (valid) KeyStatus.VALID else KeyStatus.INVALID) }
        }
    }

    fun clearKey(context: Context) {
        viewModelScope.launch {
            AppPreferences.clearGeminiKey(context)
            _state.update { UiState() }
        }
    }
}
