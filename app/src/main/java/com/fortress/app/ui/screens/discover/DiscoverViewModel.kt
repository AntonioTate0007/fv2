package com.fortress.app.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.app.data.model.Portfolio
import com.fortress.app.data.repository.FortressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DiscoverViewModel(
    private val repo: FortressRepository = FortressRepository()
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val portfolios: List<Portfolio> = emptyList(),
        val followedIds: Set<String> = emptySet(),
        val selected: Portfolio? = null,
        val busyId: String? = null,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val portfolios = repo.portfolios()
                val followed = repo.follows().follows.keys
                portfolios to followed
            }.onSuccess { (portfolios, followed) ->
                _state.update { it.copy(loading = false, portfolios = portfolios, followedIds = followed) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load") }
            }
        }
    }

    fun open(p: Portfolio) = _state.update { it.copy(selected = p) }
    fun closeDetail() = _state.update { it.copy(selected = null) }

    fun toggleFollow(p: Portfolio) {
        val following = p.id !in _state.value.followedIds
        viewModelScope.launch {
            _state.update { it.copy(busyId = p.id) }
            runCatching { repo.follow(p.id, following) }
                .onSuccess { st ->
                    _state.update { it.copy(followedIds = st.follows.keys, busyId = null) }
                }
                .onFailure { _state.update { it.copy(busyId = null) } }
        }
    }
}
