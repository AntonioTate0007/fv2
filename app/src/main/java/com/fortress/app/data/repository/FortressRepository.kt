package com.fortress.app.data.repository

import android.content.Context
import com.fortress.app.BuildConfig
import com.fortress.app.data.api.ApiClient
import com.fortress.app.data.api.FortressApiService
import com.fortress.app.data.gemini.GeminiService
import com.fortress.app.data.model.AccountSnapshot
import com.fortress.app.data.model.ActivityItem
import com.fortress.app.data.model.AutopilotSettings
import com.fortress.app.data.model.FollowEntry
import com.fortress.app.data.model.FollowRequest
import com.fortress.app.data.model.FollowState
import com.fortress.app.data.model.Portfolio
import com.fortress.app.data.model.PortfolioHolding
import com.fortress.app.data.model.PositionLite
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for the app.
 *
 *   • Live backend when API_BASE_URL points at a real server.
 *   • Rich mock data otherwise, so the app is fully demoable offline.
 */
class FortressRepository(
    private val context: Context? = null,
    private val service: FortressApiService = ApiClient.service
) {
    // ── Catalog ───────────────────────────────────────────────────────────────

    suspend fun portfolios(): List<Portfolio> {
        if (!USE_MOCK_DATA) return service.portfolios()
        delay(250); return MockData.portfolios
    }

    suspend fun portfolio(id: String): Portfolio {
        if (!USE_MOCK_DATA) return service.portfolio(id)
        delay(150); return MockData.portfolios.first { it.id == id }
    }

    suspend fun refreshPortfolio(id: String): Portfolio {
        if (!USE_MOCK_DATA) return service.refreshPortfolio(id)
        delay(600); return MockData.portfolios.first { it.id == id }
    }

    // ── Account + activity (engine-written) ─────────────────────────────────────

    suspend fun account(): AccountSnapshot {
        if (!USE_MOCK_DATA) return service.account()
        delay(200); return MockData.account
    }

    suspend fun activity(limit: Int = 50): List<ActivityItem> {
        if (!USE_MOCK_DATA) return service.activity(limit)
        delay(200); return MockData.activity
    }

    // ── Follows ─────────────────────────────────────────────────────────────────

    suspend fun follows(): FollowState {
        if (!USE_MOCK_DATA) return service.follows()
        return FollowState(MockData.follows.toMap())
    }

    suspend fun follow(portfolioId: String, following: Boolean, allocationPct: Double? = null): FollowState {
        if (!USE_MOCK_DATA) return service.follow(FollowRequest(portfolioId, following, allocationPct))
        if (following) {
            MockData.follows[portfolioId] = FollowEntry(allocationPct ?: 1.0, System.currentTimeMillis() / 1000.0)
        } else {
            MockData.follows.remove(portfolioId)
        }
        return FollowState(MockData.follows.toMap())
    }

    // ── Settings ─────────────────────────────────────────────────────────────────

    suspend fun settings(): AutopilotSettings {
        if (!USE_MOCK_DATA) return service.settings()
        return MockData.settings
    }

    suspend fun updateSettings(settings: AutopilotSettings): AutopilotSettings {
        if (!USE_MOCK_DATA) return service.updateSettings(settings)
        MockData.settings = settings
        return settings
    }

    // ── Gemini key check ──────────────────────────────────────────────────────

    suspend fun testGeminiKey(key: String): Boolean =
        runCatching { GeminiService(key).testConnection() }.getOrDefault(false)

    companion object {
        // Mock mode unless a real backend URL is set in local.properties.
        val USE_MOCK_DATA: Boolean =
            BuildConfig.API_BASE_URL.isBlank() ||
            BuildConfig.API_BASE_URL.contains("example.com", ignoreCase = true)
    }
}

// ── Mock data ───────────────────────────────────────────────────────────────────

private object MockData {

    val follows = ConcurrentHashMap<String, FollowEntry>().apply {
        put("ai-flagship", FollowEntry(allocationPct = 1.0, followedAt = 0.0))
    }

    var settings = AutopilotSettings(
        killSwitch = false,
        allocatedCapital = 500.0,
        maxPositionPct = 0.25,
        driftThresholdPct = 0.03,
        rebalanceCadence = "daily",
        account = "••••9584"
    )

    private fun curve(start: Double, vararg deltas: Double): List<Double> {
        val out = mutableListOf(start)
        var v = start
        for (d in deltas) { v *= (1 + d); out.add(v) }
        return out
    }

    val portfolios: List<Portfolio> = listOf(
        Portfolio(
            id = "ai-flagship",
            name = "AI Flagship Fund",
            tagline = "Gemini-picked mega-cap growth",
            description = "A concentrated basket of high-quality mega-cap equities and broad-market " +
                "ETFs, re-weighted by an AI model that reads momentum and market breadth. Autopilot " +
                "keeps your Robinhood account matched to these target weights.",
            category = "AI",
            managerLabel = "Autopilot AI",
            iconEmoji = "🧠",
            ytdReturnPct = 0.431,
            followers = 248_512,
            holdings = listOf(
                PortfolioHolding("NVDA", "NVIDIA", 0.22),
                PortfolioHolding("MSFT", "Microsoft", 0.18),
                PortfolioHolding("AAPL", "Apple", 0.16),
                PortfolioHolding("AMZN", "Amazon", 0.14),
                PortfolioHolding("GOOGL", "Alphabet", 0.12),
                PortfolioHolding("SPY", "S&P 500 ETF", 0.10),
                PortfolioHolding("QQQ", "Nasdaq-100 ETF", 0.08),
            ),
            performance = curve(100.0, .03, .02, -.01, .04, .03, -.02, .05, .04, .02, .06),
            rationale = "Risk-on tape with broad participation. Overweight NVDA and MSFT on " +
                "AI-capex momentum; ETF sleeve dampens single-name drawdowns."
        ),
        Portfolio(
            id = "index-core",
            name = "Index Core",
            tagline = "Set-and-forget broad market",
            description = "A simple, low-turnover core of broad-market and Nasdaq ETFs. The calm " +
                "anchor of a portfolio.",
            category = "Index",
            managerLabel = "Autopilot",
            iconEmoji = "📈",
            ytdReturnPct = 0.182,
            followers = 511_204,
            holdings = listOf(
                PortfolioHolding("SPY", "S&P 500 ETF", 0.50),
                PortfolioHolding("QQQ", "Nasdaq-100 ETF", 0.30),
                PortfolioHolding("VTI", "Total Market ETF", 0.20),
            ),
            performance = curve(100.0, .01, .02, .01, .015, -.005, .02, .01, .012, .008, .015),
            rationale = "Diversified beta. Rebalances rarely; lowest cost, lowest drama."
        ),
        Portfolio(
            id = "magnificent-seven",
            name = "Magnificent Seven",
            tagline = "The mega-cap tech leaders",
            description = "Equal-conviction exposure to the seven mega-cap names that drive the " +
                "index. Higher volatility, higher beta.",
            category = "Thematic",
            managerLabel = "Autopilot",
            iconEmoji = "🚀",
            ytdReturnPct = 0.367,
            followers = 132_880,
            holdings = listOf(
                PortfolioHolding("AAPL", "Apple", 0.15),
                PortfolioHolding("MSFT", "Microsoft", 0.15),
                PortfolioHolding("NVDA", "NVIDIA", 0.15),
                PortfolioHolding("AMZN", "Amazon", 0.14),
                PortfolioHolding("GOOGL", "Alphabet", 0.14),
                PortfolioHolding("META", "Meta", 0.14),
                PortfolioHolding("TSLA", "Tesla", 0.13),
            ),
            performance = curve(100.0, .04, -.02, .05, .03, -.03, .06, .02, .04, -.01, .05),
            rationale = "Concentrated leadership trade. Sized equally to avoid single-name dominance."
        ),
    )

    val account = AccountSnapshot(
        accountMasked = "••••9584",
        portfolioValue = 512.74,
        buyingPower = 38.10,
        todayChange = 6.42,
        todayChangePct = 0.0127,
        deployed = 474.64,
        connected = true,
        positions = listOf(
            PositionLite("NVDA", 0.71, 104.30, 138.0, 0.061),
            PositionLite("MSFT", 0.19, 85.40, 432.0, 0.041),
            PositionLite("AAPL", 0.27, 76.20, 268.0, -0.012),
            PositionLite("AMZN", 0.31, 66.50, 205.0, 0.028),
            PositionLite("GOOGL", 0.30, 57.10, 188.0, 0.034),
            PositionLite("SPY", 0.08, 47.85, 598.0, 0.015),
            PositionLite("QQQ", 0.07, 36.50, 521.0, 0.019),
        ),
        updatedAt = 0.0
    )

    val activity = listOf(
        ActivityItem("a1", 0.0, "TRADE", "Your trades are complete",
            "Autopilot bought 7 positions to match AI Flagship Fund", amount = 474.64),
        ActivityItem("a2", 0.0, "REBALANCE", "Portfolio rebalanced",
            "Trimmed AAPL, added NVDA to track target weights", ticker = "NVDA"),
        ActivityItem("a3", 0.0, "TRADE", "Bought NVDA", "0.71 shares @ \$138.00", ticker = "NVDA", amount = 104.30),
        ActivityItem("a4", 0.0, "FOLLOW", "Started following", "AI Flagship Fund — 100% allocation"),
        ActivityItem("a5", 0.0, "SYSTEM", "Autopilot engaged",
            "Connected to Robinhood ••••9584 (cash). Equities only."),
    )
}
