package com.fortress.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.app.data.model.AccountSnapshot
import com.fortress.app.data.model.Portfolio
import com.fortress.app.data.repository.FortressRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repo: FortressRepository = FortressRepository()
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val account: AccountSnapshot? = null,
        val followed: List<Portfolio> = emptyList(),
        val killSwitch: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val account = repo.account()
                val follows = repo.follows().follows.keys
                val all = repo.portfolios()
                val followed = all.filter { it.id in follows }
                val settings = repo.settings()
                Triple(account, followed, settings.killSwitch)
            }.onSuccess { (account, followed, kill) ->
                _state.update {
                    it.copy(loading = false, account = account, followed = followed, killSwitch = kill)
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load") }
            }
        }
    }
}
