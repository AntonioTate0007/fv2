package com.fortress.trader.data.model

/** A single open options position shown on the Portfolio screen. */
data class Position(
    val ticker: String,
    val strategy: String,
    val premium: String,
    val risk: RiskLevel
)

enum class RiskLevel(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High")
}
