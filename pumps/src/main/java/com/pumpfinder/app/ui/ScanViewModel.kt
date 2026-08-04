package com.pumpfinder.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pumpfinder.app.data.PumpCandidate
import com.pumpfinder.app.data.PumpRepository
import com.pumpfinder.app.data.ScanResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScanUiState(
    val loading: Boolean = false,
    val source: String = "—",         // live | mock | offline
    val asOfMs: Long = 0L,
    val candidates: List<PumpCandidate> = emptyList(),
    val error: String? = null,
)

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PumpRepository(app.applicationContext)
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private var loop: Job? = null

    init {
        refresh()
        startAutoRefresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = repo.scan()
            result.fold(
                onSuccess = { resp: ScanResponse ->
                    _state.update {
                        it.copy(
                            loading = false,
                            source = resp.source,
                            asOfMs = (resp.asOf * 1000).toLong(),
                            candidates = resp.candidates,
                            error = null,
                        )
                    }
                },
                onFailure = { t ->
                    _state.update {
                        it.copy(
                            loading = false,
                            source = "offline",
                            error = t.message ?: "Network error",
                        )
                    }
                }
            )
        }
    }

    private fun startAutoRefresh() {
        loop?.cancel()
        loop = viewModelScope.launch {
            while (true) {
                delay(30_000)
                refresh()
            }
        }
    }
}
