package com.fortress.app.ui.screens.radar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.app.data.model.ScannedTrade
import com.fortress.app.data.repository.FortressRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RadarViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FortressRepository(app.applicationContext)

    data class UiState(
        val capital: Int = CAPITAL_TIERS[1],
        val trades: List<ScannedTrade> = emptyList(),
        val loading: Boolean = false,
        val scanning: Boolean = false,
        val marketOpen: Boolean = false,
        val nextScanAtMs: Long? = null,
        val lastScannedMs: Long? = null,
        val deployingTradeId: String? = null,
        val toast: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var pollJob: Job? = null
    private var midnightJob: Job? = null

    init {
        startMidnightClearLoop()
        startPolling()
    }

    fun setCapital(capital: Int) {
        if (capital == _state.value.capital) return
        _state.update { it.copy(capital = capital) }
        startPolling()
    }

    fun refresh() = startPolling()

    fun deploy(trade: ScannedTrade, biometricToken: String) {
        viewModelScope.launch {
            _state.update { it.copy(deployingTradeId = trade.id) }
            val result = runCatching {
                repo.deploy(trade.id, _state.value.capital, biometricToken, trade)
            }
            _state.update {
                it.copy(
                    deployingTradeId = null,
                    toast = result.fold(
                        onSuccess = { resp ->
                            if (resp.success) "Deployed ${trade.ticker} — ${resp.orderId.orEmpty()}"
                            else "Deploy rejected: ${resp.message.orEmpty()}"
                        },
                        onFailure = { e -> "Deploy failed: ${e.message}" }
                    )
                )
            }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val now = ZonedDateTime.now(MARKET_TZ)
                if (isWithinScanWindow(now)) {
                    _state.update {
                        it.copy(
                            loading = it.trades.isEmpty(),
                            scanning = it.trades.isNotEmpty(),
                            marketOpen = true,
                            nextScanAtMs = null
                        )
                    }
                    fetchOnce()
                    delay(POLL_INTERVAL_MS)
                } else {
                    val nextMs = nextScanStartMillis(now)
                    _state.update {
                        it.copy(
                            loading = false,
                            scanning = false,
                            marketOpen = false,
                            nextScanAtMs = nextMs
                        )
                    }
                    val sleepMs = (nextMs - System.currentTimeMillis()).coerceAtLeast(60_000L)
                    delay(sleepMs)
                }
            }
        }
    }

    private fun startMidnightClearLoop() {
        midnightJob?.cancel()
        midnightJob = viewModelScope.launch {
            while (isActive) {
                val now = ZonedDateTime.now()
                val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
                val sleepMs = nextMidnight.toInstant().toEpochMilli() - System.currentTimeMillis()
                delay(sleepMs.coerceAtLeast(1_000L))
                _state.update { it.copy(trades = emptyList(), lastScannedMs = null) }
            }
        }
    }

    private suspend fun fetchOnce() {
        val trades = runCatching { repo.scan(_state.value.capital) }.getOrDefault(_state.value.trades)
        _state.update {
            it.copy(
                trades = trades,
                loading = false,
                scanning = false,
                lastScannedMs = System.currentTimeMillis()
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        midnightJob?.cancel()
    }

    companion object {
        val CAPITAL_TIERS = listOf(500, 1000, 2500, 5000)
        const val POLL_INTERVAL_MS = 30_000L
        private val MARKET_TZ: ZoneId = ZoneId.of("America/New_York")
        private val SCAN_START: LocalTime = LocalTime.of(9, 45)
        private val SCAN_END: LocalTime = LocalTime.of(16, 0)

        // NYSE full-day closures. Update annually. Source: nyse.com/markets/hours-calendars
        private val MARKET_HOLIDAYS: Set<LocalDate> = setOf(
            // 2026
            LocalDate.of(2026, 1, 1),   // New Year's Day
            LocalDate.of(2026, 1, 19),  // MLK Day
            LocalDate.of(2026, 2, 16),  // Presidents' Day
            LocalDate.of(2026, 4, 3),   // Good Friday
            LocalDate.of(2026, 5, 25),  // Memorial Day
            LocalDate.of(2026, 6, 19),  // Juneteenth
            LocalDate.of(2026, 7, 3),   // Independence Day (observed)
            LocalDate.of(2026, 9, 7),   // Labor Day
            LocalDate.of(2026, 11, 26), // Thanksgiving
            LocalDate.of(2026, 12, 25), // Christmas
            // 2027
            LocalDate.of(2027, 1, 1),
            LocalDate.of(2027, 1, 18),
            LocalDate.of(2027, 2, 15),
            LocalDate.of(2027, 3, 26),
            LocalDate.of(2027, 5, 31),
            LocalDate.of(2027, 6, 18),  // Juneteenth observed (Jun 19 = Saturday)
            LocalDate.of(2027, 7, 5),   // July 4 observed (Jul 4 = Sunday)
            LocalDate.of(2027, 9, 6),
            LocalDate.of(2027, 11, 25),
            LocalDate.of(2027, 12, 24)  // Dec 25 = Saturday → observed Friday
        )

        private fun isMarketDay(date: LocalDate): Boolean {
            val d = date.dayOfWeek
            if (d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY) return false
            return date !in MARKET_HOLIDAYS
        }

        private fun isWithinScanWindow(now: ZonedDateTime): Boolean {
            if (!isMarketDay(now.toLocalDate())) return false
            val t = now.toLocalTime()
            return !t.isBefore(SCAN_START) && t.isBefore(SCAN_END)
        }

        private fun nextScanStartMillis(now: ZonedDateTime): Long {
            var candidate = now.toLocalDate().atTime(SCAN_START).atZone(MARKET_TZ)
            if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
            while (!isMarketDay(candidate.toLocalDate())) {
                candidate = candidate.plusDays(1)
            }
            return candidate.toInstant().toEpochMilli()
        }
    }
}
