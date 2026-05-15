package com.pumpfinder.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pumpfinder.app.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.pumpsDataStore by preferencesDataStore(name = "pumps")

/**
 * Single repository the UI and the WorkManager poller both talk to. The
 * "seen" set is persisted in DataStore so the background worker doesn't
 * re-alert on the same ticker every 15 minutes after a reboot.
 */
class PumpRepository(private val ctx: Context) {

    private val seenKey = stringSetPreferencesKey("seen_tickers")
    private val minScoreKey = intPreferencesKey("min_alert_score")

    /** When true the app uses canned data instead of hitting the backend. */
    val isMockMode: Boolean
        get() = BuildConfig.BACKEND_URL.contains("example.com") || BuildConfig.BACKEND_URL.isBlank()

    suspend fun scan(): Result<ScanResponse> = runCatching {
        if (isMockMode) mockScan() else PumpApiClient.service.scan()
    }

    suspend fun registerFcmToken(token: String) {
        if (isMockMode) return
        runCatching { PumpApiClient.service.registerToken(FcmTokenRequest(token)) }
            .onFailure { Log.w(TAG, "registerToken failed: ${it.message}") }
    }

    suspend fun minAlertScore(): Int =
        ctx.pumpsDataStore.data.map { it[minScoreKey] ?: DEFAULT_MIN_SCORE }.first()

    suspend fun setMinAlertScore(score: Int) {
        ctx.pumpsDataStore.edit { it[minScoreKey] = score.coerceIn(0, 100) }
    }

    suspend fun seenTickers(): Set<String> =
        ctx.pumpsDataStore.data.map { it[seenKey] ?: emptySet() }.first()

    suspend fun markSeen(tickers: Collection<String>) {
        if (tickers.isEmpty()) return
        ctx.pumpsDataStore.edit { prefs ->
            val current: Set<String> = prefs[seenKey] ?: emptySet()
            // Cap to last 200 — plenty of dedup headroom, bounded memory.
            prefs[seenKey] = (current + tickers).toList().takeLast(200).toSet()
        }
    }

    private fun mockScan(): ScanResponse {
        val now = System.currentTimeMillis() / 1000.0
        return ScanResponse(
            asOf = now,
            source = "mock",
            count = MOCK.size,
            candidates = MOCK,
        )
    }

    companion object {
        const val TAG = "PumpRepository"
        const val DEFAULT_MIN_SCORE = 85
        private val MOCK = listOf(
            PumpCandidate(
                ticker = "INVO", name = "INVO Bioscience",
                price = 1.47, changePct = 12.20, volume = 4_300_000, avgVolume = 780_000,
                relVol = 5.5, floatShares = 7_200_000, marketCap = 24_000_000,
                score = 73,
                reasons = listOf("5.5× relative volume", "+12.2% intraday — early in the move",
                                 "7.2M float — tight", "\$1.47 — sub-\$5 pump zone"),
                yahooUrl = "https://finance.yahoo.com/quote/INVO",
                fbSearchUrl = "https://www.facebook.com/search/posts/?q=%24INVO",
                stocktwitsUrl = "https://stocktwits.com/symbol/INVO",
            ),
            PumpCandidate(
                ticker = "PEGY", name = "Pineapple Energy",
                price = 0.84, changePct = 14.80, volume = 9_400_000, avgVolume = 2_100_000,
                relVol = 4.5, floatShares = 22_900_000, marketCap = 17_000_000,
                score = 62,
                reasons = listOf("4.5× relative volume", "+14.8% intraday — early in the move",
                                 "22.9M float — tight", "\$0.84 — sub-\$5 pump zone"),
                yahooUrl = "https://finance.yahoo.com/quote/PEGY",
                fbSearchUrl = "https://www.facebook.com/search/posts/?q=%24PEGY",
                stocktwitsUrl = "https://stocktwits.com/symbol/PEGY",
            ),
        )
    }
}
