package com.fortress.app.ui.screens.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.app.data.model.ActivityItem
import com.fortress.app.data.repository.FortressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ActivityViewModel(
    private val repo: FortressRepository = FortressRepository()
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val items: List<ActivityItem> = emptyList(),
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repo.activity() }
                .onSuccess { items -> _state.update { it.copy(loading = false, items = items) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Failed to load") } }
        }
    }
}
