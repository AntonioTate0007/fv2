package com.fortress.app.data.model

import kotlinx.serialization.Serializable

/** A scanned trade opportunity surfaced on the Radar screen. */
@Serializable
data class ScannedTrade(
    val id: String,
    val ticker: String,
    val strategy: StrategyType,
    val shortStrike: Double,
    val longStrike: Double,
    val expiration: String,            // ISO date string (yyyy-MM-dd)
    val dte: Int,                      // Days to expiration — Theta sweet spot is 7..14
    val estimatedCreditPerContract: Double,
    val safetyBufferPct: Double,       // e.g. 0.082 = 8.2%
    val underlyingPrice: Double,
    val probabilityOfProfit: Double,   // 0..1
    val ivRank: Double,                // 0..1, IV percentile rank over trailing year
    val earningsClear: Boolean         // true = no earnings during trade window
) {
    /**
     * Composite heat score (0..1). Weighted from IV rank, probability of profit,
     * and safety buffer — the three signals that best predict a fat, low-risk premium.
     * Used to drive the 🔥 rating visible on each Trade Card.
     */
    val heatScore: Double
        get() = (ivRank * 0.40 + probabilityOfProfit * 0.40 + (safetyBufferPct / 0.08).coerceAtMost(1.0) * 0.20)
            .coerceIn(0.0, 1.0)

    /** 0 = no flame, 1 = 🔥, 2 = 🔥🔥, 3 = 🔥🔥🔥 */
    val flames: Int
        get() = when {
            heatScore >= 0.82 -> 3
            heatScore >= 0.70 -> 2
            heatScore >= 0.55 -> 1
            else -> 0
        }

    /** Human label such as "$265 / $260 Put Spread". */
    val strategyLabel: String
        get() = when (strategy) {
            StrategyType.PUT_CREDIT_SPREAD ->
                "$${shortStrike.toInt()} / $${longStrike.toInt()} Put Spread"
            StrategyType.CALL_CREDIT_SPREAD ->
                "$${shortStrike.toInt()} / $${longStrike.toInt()} Call Spread"
            StrategyType.CASH_SECURED_PUT ->
                "$${shortStrike.toInt()} Cash-Secured Put"
        }
}

@Serializable
enum class StrategyType { PUT_CREDIT_SPREAD, CALL_CREDIT_SPREAD, CASH_SECURED_PUT }

/** Body sent to the backend when the user taps DEPLOY (after biometric auth). */
@Serializable
data class DeployRequest(
    val tradeId: String,
    val capitalDeployment: Int,        // $500 / $1000 / $2500 / $5000
    val biometricToken: String,        // proof that biometrics passed
    /** Full trade snapshot — the backend uses this to resolve OCC contracts and
     *  size the order without re-scanning the chain. Optional for backwards-compat
     *  with mock backends. */
    val trade: ScannedTrade? = null
)

@Serializable
data class DeployResponse(
    val success: Boolean,
    val orderId: String? = null,
    val message: String? = null
)
