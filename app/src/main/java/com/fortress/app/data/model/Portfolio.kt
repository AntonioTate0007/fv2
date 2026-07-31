package com.fortress.app.data.model

import kotlinx.serialization.Serializable

/**
 * A model portfolio the user can follow. Autopilot keeps the connected brokerage
 * matched to the followed portfolios' target weights.
 */
@Serializable
data class Portfolio(
    val id: String,
    val name: String,
    val tagline: String,
    val description: String,
    val category: String,                 // e.g. "AI", "Index", "Thematic"
    val managerLabel: String,             // e.g. "Autopilot AI"
    val iconEmoji: String,
    val ytdReturnPct: Double,             // e.g. 0.431 = +43.1%
    val followers: Int,
    val holdings: List<PortfolioHolding> = emptyList(),
    val performance: List<Double> = emptyList(),   // normalized equity curve points
    val rationale: String = ""            // AI-generated "why these picks"
)

@Serializable
data class PortfolioHolding(
    val ticker: String,
    val name: String,
    val weight: Double                    // 0..1, sums to ~1 across the portfolio
)

/** Snapshot of the connected Robinhood account, written by the autopilot engine. */
@Serializable
data class AccountSnapshot(
    val accountMasked: String,            // e.g. "••••9584"
    val portfolioValue: Double,
    val buyingPower: Double,
    val todayChange: Double,
    val todayChangePct: Double,
    val deployed: Double,                 // $ currently invested by autopilot
    val positions: List<PositionLite> = emptyList(),
    val updatedAt: Double = 0.0,
    val connected: Boolean = true
)

@Serializable
data class PositionLite(
    val ticker: String,
    val shares: Double,
    val marketValue: Double,
    val avgCost: Double,
    val unrealizedPct: Double
)

/** One entry in the Activity feed — mirrors Autopilot's "your trades are complete". */
@Serializable
data class ActivityItem(
    val id: String,
    val at: Double,                       // epoch seconds
    val type: String,                     // TRADE | REBALANCE | FOLLOW | SYSTEM
    val title: String,
    val subtitle: String,
    val ticker: String? = null,
    val amount: Double? = null
)

/** Guardrails the user edits and the autopilot engine obeys. */
@Serializable
data class AutopilotSettings(
    val killSwitch: Boolean = false,
    val allocatedCapital: Double = 500.0,
    val maxPositionPct: Double = 0.25,
    val driftThresholdPct: Double = 0.03,
    val rebalanceCadence: String = "daily",
    val account: String = ""
)

@Serializable
data class FollowRequest(
    val portfolioId: String,
    val following: Boolean,
    val allocationPct: Double? = null
)

@Serializable
data class FollowState(
    val follows: Map<String, FollowEntry> = emptyMap()
)

@Serializable
data class FollowEntry(
    val allocationPct: Double = 1.0,
    val followedAt: Double = 0.0
)
