package com.fortress.app.ui.screens.armory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.app.data.model.ActivePosition
import com.fortress.app.data.repository.FortressRepository
import com.fortress.app.notification.ProfitAlertManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ArmoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FortressRepository(app.applicationContext)
    private val alerted = mutableSetOf<String>()
    private var pollJob: Job? = null

    data class UiState(
        val positions: List<ActivePosition> = emptyList(),
        val loading: Boolean = false,
        val closingPositionId: String? = null,
        val toast: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { startPolling() }

    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _state.update { it.copy(loading = it.positions.isEmpty()) }
            fetchOnce()
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                fetchOnce()
            }
        }
    }

    fun refresh() = startPolling()

    fun close(position: ActivePosition, biometricToken: String) {
        viewModelScope.launch {
            _state.update { it.copy(closingPositionId = position.id) }
            val result = runCatching { repo.closePosition(position.id, biometricToken) }
            _state.update { current ->
                current.copy(
                    closingPositionId = null,
                    positions = if (result.getOrNull()?.success == true)
                        current.positions.filterNot { it.id == position.id }
                    else current.positions,
                    toast = result.fold(
                        onSuccess = { resp -> resp.message ?: "Closed ${position.ticker}." },
                        onFailure = { "Close failed: ${it.message}" }
                    )
                )
            }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    override fun onCleared() { super.onCleared(); pollJob?.cancel() }

    private suspend fun fetchOnce() {
        val positions = runCatching { repo.positions() }.getOrDefault(_state.value.positions)
        _state.update { it.copy(positions = positions, loading = false) }

        // Fire local "time to sell" notification for each new profit target crossed.
        val ctx = getApplication<Application>().applicationContext
        positions.filter { it.atProfitTarget && it.id !in alerted }.forEach { pos ->
            alerted += pos.id
            ProfitAlertManager.notify(ctx, pos)
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 60_000L
    }
}
