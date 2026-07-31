package com.fortress.trader.data

import com.fortress.trader.data.model.Position
import com.fortress.trader.data.model.RiskLevel

/**
 * Static sample data backing the demo screens. Swap this object out for a
 * repository (network / DB) when wiring the app to a live backend.
 */
object SampleData {

    const val PORTFOLIO_VALUE = "$17,821.97"
    const val TODAY_CHANGE = "+$400.35 Today"

    val positions = listOf(
        Position("ORCL", "Iron Condor", "$1.79", RiskLevel.HIGH),
        Position("AAPL", "Iron Condor", "$1.25", RiskLevel.LOW),
        Position("NVDA", "Bull Put Spread", "$2.10", RiskLevel.MEDIUM)
    )

    const val RISK_SCORE = 23

    val earningsExposure = listOf(
        "ORCL — Earnings Tomorrow",
        "NVDA — Earnings This Week"
    )
}
