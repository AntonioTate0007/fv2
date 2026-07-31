package com.fortress.trader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fortress.trader.data.AccountSummary
import com.fortress.trader.data.AlpacaClient
import com.fortress.trader.data.AppSettings
import com.fortress.trader.data.EquityPosition
import com.fortress.trader.data.PositionParser
import com.fortress.trader.data.SettingsStore
import com.fortress.trader.data.Spread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PortfolioState(
    val loading: Boolean = false,
    val error: String? = null,
    val account: AccountSummary? = null,
    val spreads: List<Spread> = emptyList(),
    val equities: List<EquityPosition> = emptyList(),
    val loaded: Boolean = false
)

class PlaysViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app.applicationContext)

    val settings: StateFlow<AppSettings> = store.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    private val _portfolio = MutableStateFlow(PortfolioState())
    val portfolio: StateFlow<PortfolioState> = _portfolio.asStateFlow()

    fun saveSettings(newSettings: AppSettings, thenRefresh: Boolean = true) {
        viewModelScope.launch {
            store.save(newSettings)
            if (thenRefresh && newSettings.isConfigured) refresh(newSettings)
        }
    }

    fun refresh(current: AppSettings = settings.value) {
        if (!current.isConfigured) {
            _portfolio.value = PortfolioState(error = "Add your Alpaca API key and secret in Settings.")
            return
        }
        _portfolio.value = _portfolio.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val api = AlpacaClient.create(
                    keyId = current.alpacaKey.trim(),
                    secret = current.alpacaSecret.trim(),
                    paper = current.paperTrading
                )
                val account = PositionParser.account(api.getAccount())
                val (spreads, equities) = PositionParser.classify(api.getPositions())
                _portfolio.value = PortfolioState(
                    loading = false,
                    account = account,
                    spreads = spreads,
                    equities = equities,
                    loaded = true
                )
            } catch (e: Exception) {
                _portfolio.value = _portfolio.value.copy(
                    loading = false,
                    error = friendlyError(e)
                )
            }
        }
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("401") || msg.contains("403") ->
                "Alpaca rejected the credentials (401/403). Check the key, secret, and paper/live toggle."
            msg.contains("Unable to resolve host") || msg.contains("timeout", ignoreCase = true) ->
                "Network error reaching Alpaca. Check your connection and try again."
            else -> "Couldn't load data: $msg"
        }
    }
}
