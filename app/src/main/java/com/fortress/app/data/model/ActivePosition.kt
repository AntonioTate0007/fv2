package com.fortress.app.data.model

import kotlinx.serialization.Serializable

/** An open position monitored on the Armory screen. */
@Serializable
data class ActivePosition(
    val id: String,
    val ticker: String,
    val strategyLabel: String,
    val shortStrike: Double,           // user's safety floor
    val underlyingPrice: Double,       // live price
    val entryPremium: Double,          // credit collected on entry
    val currentPremium: Double,        // present cost to close
    val expiration: String,
    val contracts: Int
) {
    /** Profit captured so far, expressed as a fraction of the entry credit (0..1+). */
    val profitFraction: Double
        get() = if (entryPremium <= 0) 0.0
                else ((entryPremium - currentPremium) / entryPremium).coerceAtLeast(0.0)

    /** True once the trade has hit the 50% profit-take rule. */
    val atProfitTarget: Boolean get() = profitFraction >= 0.5

    /**
     * Distance between underlying price and the short strike, expressed as a fraction
     * of the strike. Positive = the moat is intact (price comfortably above strike for
     * a put spread). 0 = price has reached the wall.
     */
    val moatFraction: Double
        get() = if (shortStrike <= 0) 0.0
                else ((underlyingPrice - shortStrike) / shortStrike).coerceIn(-1.0, 1.0)
}

@Serializable
data class CloseRequest(val positionId: String, val biometricToken: String)

@Serializable
data class CloseResponse(val success: Boolean, val message: String? = null)
