package com.mark39.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mark39.assistant.data.Prefs
import com.mark39.assistant.data.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val geminiKey: String = "",
        val openRouterKey: String = "",
        val provider: Provider = Provider.AUTO,
        val orModel: String = Prefs.DEFAULT_OR_MODEL,
        val loaded: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = Prefs.snapshot(getApplication())
            _state.update {
                it.copy(
                    geminiKey = s.geminiKey, openRouterKey = s.openRouterKey,
                    provider = s.provider, orModel = s.orModel, loaded = true
                )
            }
        }
    }

    fun setGeminiKey(v: String) = _state.update { it.copy(geminiKey = v) }
    fun setOpenRouterKey(v: String) = _state.update { it.copy(openRouterKey = v) }
    fun setOrModel(v: String) = _state.update { it.copy(orModel = v) }
    fun setProvider(p: Provider) = _state.update { it.copy(provider = p) }

    fun save() {
        val s = _state.value
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            Prefs.setGeminiKey(ctx, s.geminiKey)
            Prefs.setOpenRouterKey(ctx, s.openRouterKey)
            Prefs.setProvider(ctx, s.provider)
            Prefs.setOrModel(ctx, s.orModel)
        }
    }
}
