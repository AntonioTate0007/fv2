package com.fortress.trader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User-entered config. Keys are stored on-device in the app's private DataStore
 * (sandboxed from other apps). Use Alpaca keys scoped to what you need.
 */
data class AppSettings(
    val alpacaKey: String = "",
    val alpacaSecret: String = "",
    val paperTrading: Boolean = true,
    val automation: Boolean = false
) {
    val isConfigured: Boolean
        get() = alpacaKey.isNotBlank() && alpacaSecret.isNotBlank()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "plays_settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val ALPACA_KEY = stringPreferencesKey("alpaca_key")
        val ALPACA_SECRET = stringPreferencesKey("alpaca_secret")
        val PAPER = booleanPreferencesKey("paper_trading")
        val AUTOMATION = booleanPreferencesKey("automation")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            alpacaKey = p[Keys.ALPACA_KEY] ?: "",
            alpacaSecret = p[Keys.ALPACA_SECRET] ?: "",
            paperTrading = p[Keys.PAPER] ?: true,
            automation = p[Keys.AUTOMATION] ?: false
        )
    }

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.ALPACA_KEY] = settings.alpacaKey
            p[Keys.ALPACA_SECRET] = settings.alpacaSecret
            p[Keys.PAPER] = settings.paperTrading
            p[Keys.AUTOMATION] = settings.automation
        }
    }
}
