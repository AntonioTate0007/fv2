package com.fortress.app.data.repository

import android.content.Context
import com.fortress.app.BuildConfig
import com.fortress.app.data.api.ApiClient
import com.fortress.app.data.api.FortressApiService
import com.fortress.app.data.gemini.GeminiService
import com.fortress.app.data.model.ActivePosition
import com.fortress.app.data.model.CloseRequest
import com.fortress.app.data.model.CloseResponse
import com.fortress.app.data.model.DeployRequest
import com.fortress.app.data.model.DeployResponse
import com.fortress.app.data.model.RiskOfficerRequest
import com.fortress.app.data.model.ScannedTrade
import com.fortress.app.data.model.StrategyType
import com.fortress.app.data.preferences.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID
import kotlin.random.Random

/**
 * Single source of truth.
 *
 * Priority order for each operation:
 *   1. Gemini (if API key is saved in DataStore)
 *   2. Live backend (if USE_MOCK_DATA = false and backend URL is set)
 *   3. Mock data (default / offline fallback)
 */
class FortressRepository(
    private val context: Context? = null,
    private val service: FortressApiService = ApiClient.service
) {
    private suspend fun gemini(): GeminiService? {
        val key = context?.let { AppPreferences.geminiKeyFlow(it).firstOrNull() }.orEmpty()
        return if (key.isNotBlank()) GeminiService(key) else null
    }

    // ── Radar ─────────────────────────────────────────────────────────────────

    suspend fun scan(capitalDeployment: Int): List<ScannedTrade> {
        gemini()?.let { g ->
            val trades = runCatching { g.scanForPlays(capitalDeployment) }.getOrNull()
            if (!trades.isNullOrEmpty()) return trades
        }
        if (!USE_MOCK_DATA) return service.scan(capitalDeployment)
        delay(350)
        return MockData.scan(capitalDeployment)
    }

    suspend fun deploy(
        tradeId: String,
        capital: Int,
        biometricToken: String,
        trade: ScannedTrade? = null
    ): DeployResponse {
        if (USE_MOCK_DATA) { delay(700); return DeployResponse(true, "MOCK-${UUID.randomUUID()}") }
        return service.deploy(DeployRequest(tradeId, capital, biometricToken, trade))
    }

    // ── Armory ────────────────────────────────────────────────────────────────

    suspend fun positions(): List<ActivePosition> {
        if (USE_MOCK_DATA) { delay(250); return MockData.positions() }
        return service.positions()
    }

    suspend fun closePosition(positionId: String, biometricToken: String): CloseResponse {
        if (USE_MOCK_DATA) { delay(500); return CloseResponse(true, "Position closed at market.") }
        return service.closePosition(CloseRequest(positionId, biometricToken))
    }

    // ── Risk Officer ──────────────────────────────────────────────────────────

    suspend fun askRiskOfficer(
        prompt: String,
        capitalContext: Int?,
        imageBase64: String? = null,
        history: List<Pair<String, String>> = emptyList()
    ): String {
        gemini()?.let { g ->
            return runCatching {
                g.askRiskOfficer(prompt, history, imageBase64)
            }.getOrElse { "Gemini error: ${it.message}" }
        }
        if (!USE_MOCK_DATA) {
            return service.askRiskOfficer(RiskOfficerRequest(prompt, capitalContext, imageBase64)).reply
        }
        delay(900)
        return MockData.officerReply(prompt, capitalContext, hasImage = imageBase64 != null)
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    suspend fun testGeminiKey(key: String): Boolean =
        runCatching { GeminiService(key).testConnection() }.getOrDefault(false)

    companion object {
        // Auto-on whenever the build is pointed at the placeholder URL. Drop a real backend
        // URL into app/build.gradle.kts (API_BASE_URL) and this flips to live calls.
        val USE_MOCK_DATA: Boolean =
            BuildConfig.API_BASE_URL.isBlank() ||
            BuildConfig.API_BASE_URL.contains("example.com", ignoreCase = true)
    }
}

// ── Mock data ─────────────────────────────────────────────────────────────────

private object MockData {
    fun scan(capital: Int): List<ScannedTrade> {
        val _unused = capital
        fun jitterCredit(base: Double) = (base + Random.nextDouble(-0.05, 0.05)).coerceIn(0.35, 0.80)
        fun jitterIv(base: Double) = (base + Random.nextDouble(-0.04, 0.04)).coerceIn(0.10, 0.99)
        return listOf(
            ScannedTrade("AAPL-PCS-265", "AAPL", StrategyType.PUT_CREDIT_SPREAD,
                265.0, 260.0, "2026-05-15", 10, jitterCredit(0.78), 0.082, 288.42, 0.86, jitterIv(0.68), true),
            ScannedTrade("MSFT-PCS-410", "MSFT", StrategyType.PUT_CREDIT_SPREAD,
                410.0, 400.0, "2026-05-15", 10, jitterCredit(0.67), 0.067, 439.10, 0.82, jitterIv(0.74), true),
            ScannedTrade("NVDA-CSP-115", "NVDA", StrategyType.CASH_SECURED_PUT,
                115.0, 115.0, "2026-05-22", 14, jitterCredit(0.54), 0.094, 127.05, 0.79, jitterIv(0.81), true),
            ScannedTrade("GOOGL-PCS-180", "GOOGL", StrategyType.PUT_CREDIT_SPREAD,
                180.0, 175.0, "2026-05-15", 10, jitterCredit(0.62), 0.071, 193.78, 0.84, jitterIv(0.59), true),
            ScannedTrade("AMZN-PCS-200", "AMZN", StrategyType.PUT_CREDIT_SPREAD,
                200.0, 195.0, "2026-05-22", 14, jitterCredit(0.71), 0.058, 212.34, 0.81, jitterIv(0.63), true)
        )
    }

    fun positions(): List<ActivePosition> = listOf(
        ActivePosition("POS-AAPL-1", "AAPL", "$265 / $260 Put Spread",
            265.0, 286.10, 78.0, 38.0, "2026-05-15", 4),
        ActivePosition("POS-MSFT-1", "MSFT", "$410 / $400 Put Spread",
            410.0, 432.55, 134.0, 71.0, "2026-05-15", 2),
        ActivePosition("POS-NVDA-1", "NVDA", "$115 Cash-Secured Put",
            115.0, 124.60, 108.0, 61.0, "2026-05-22", 1)
    )

    fun officerReply(prompt: String, capital: Int?, hasImage: Boolean): String = buildString {
        val cap = capital?.let { "$$it" } ?: "the configured capital block"
        if (hasImage) {
            append("📸 Screenshot received — analyzing the options chain...\n\n")
            append("I can see a spread setup in the image. Here's my read:\n")
            append("• Short strike appears ~7% below current price — within Moat spec.\n")
            append("• Bid/ask spread looks tight; liquidity should be fine.\n")
            append("• No earnings flag visible. Green light.\n\n")
        }
        append("Analysis of \"$prompt\" against $cap:\n")
        append("• IV rank is elevated — premium is fat. Good time to sell.\n")
        append("• 8.2% safety buffer keeps the short strike well below support.\n")
        append("• 50% profit-take trigger is armed.\n")
        append("• Recommend no more than the suggested capital block.\n\n")
        append("_(Add your Gemini API key in Settings to get live AI analysis.)_")
    }
}
