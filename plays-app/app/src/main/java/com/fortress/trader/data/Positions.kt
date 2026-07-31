package com.fortress.trader.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** A live account snapshot derived from Alpaca's `/v2/account`. */
data class AccountSummary(
    val portfolioValue: Double,
    val cash: Double,
    val dayChange: Double
)

/** A single option leg parsed from an OCC symbol plus its live position values. */
data class OptionLeg(
    val rawSymbol: String,
    val underlying: String,
    val expiry: LocalDate,
    val type: Char,        // 'C' or 'P'
    val strike: Double,
    val qty: Double,       // negative = short
    val avgEntry: Double,
    val currentPrice: Double,
    val marketValue: Double,
    val unrealizedPl: Double
)

/** Option legs on the same underlying + expiration, presented as one spread. */
data class Spread(
    val underlying: String,
    val expiry: LocalDate,
    val legs: List<OptionLeg>
) {
    val contracts: Int = legs.maxOfOrNull { abs(it.qty) }?.toInt() ?: 0
    val marketValue: Double = legs.sumOf { it.marketValue }
    val unrealizedPl: Double = legs.sumOf { it.unrealizedPl }

    /** Net cost basis across legs (negative = credit received when opened). */
    val netCostBasis: Double = legs.sumOf { it.avgEntry * it.qty * 100.0 }

    val isNetCredit: Boolean get() = netCostBasis < 0

    /** Fraction of the opening credit/debit currently captured as profit (0..1). */
    val profitFraction: Float
        get() {
            val basis = abs(netCostBasis)
            if (basis <= 0.0) return 0f
            return (unrealizedPl / basis).coerceIn(0.0, 1.0).toFloat()
        }

    val expiryLabel: String get() = expiry.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))

    /** e.g. "P 195/200 · C 225/230" built from the legs. */
    val structureLabel: String
        get() {
            val puts = legs.filter { it.type == 'P' }.map { it.strike }.sorted()
            val calls = legs.filter { it.type == 'C' }.map { it.strike }.sorted()
            val parts = mutableListOf<String>()
            if (puts.isNotEmpty()) parts += "P " + puts.joinToString("/") { fmtStrike(it) }
            if (calls.isNotEmpty()) parts += "C " + calls.joinToString("/") { fmtStrike(it) }
            return parts.joinToString("  ·  ")
        }

    private fun fmtStrike(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
}

/** A plain (non-option) position, e.g. shares of stock. */
data class EquityPosition(
    val symbol: String,
    val qty: Double,
    val marketValue: Double,
    val currentPrice: Double,
    val unrealizedPl: Double
)

object PositionParser {

    fun account(a: AlpacaAccount): AccountSummary {
        val equity = a.portfolio_value?.toDoubleOrNull()
            ?: a.equity?.toDoubleOrNull() ?: 0.0
        val last = a.last_equity?.toDoubleOrNull() ?: equity
        return AccountSummary(
            portfolioValue = equity,
            cash = a.cash?.toDoubleOrNull() ?: 0.0,
            dayChange = equity - last
        )
    }

    /**
     * Parses an OCC option symbol such as `ORCL250620P00195000`. The trailing 15
     * chars are fixed: 6-digit date (YYMMDD), 1 type char, 8-digit strike (×1000).
     * Everything before that is the underlying ticker.
     */
    fun parseOptionLeg(p: AlpacaPosition): OptionLeg? {
        val s = p.symbol
        if (s.length < 16) return null

        val type = s[s.length - 9]
        if (type != 'C' && type != 'P') return null

        val strikeRaw = s.takeLast(8).toLongOrNull() ?: return null
        val dateStr = s.substring(s.length - 15, s.length - 9)
        val underlying = s.substring(0, s.length - 15)
        if (underlying.isEmpty()) return null

        val expiry = try {
            LocalDate.parse("20${dateStr.substring(0, 2)}-${dateStr.substring(2, 4)}-${dateStr.substring(4, 6)}")
        } catch (e: Exception) {
            return null
        }

        return OptionLeg(
            rawSymbol = s,
            underlying = underlying,
            expiry = expiry,
            type = type,
            strike = strikeRaw / 1000.0,
            qty = p.qty?.toDoubleOrNull() ?: 0.0,
            avgEntry = p.avg_entry_price?.toDoubleOrNull() ?: 0.0,
            currentPrice = p.current_price?.toDoubleOrNull() ?: 0.0,
            marketValue = p.market_value?.toDoubleOrNull() ?: 0.0,
            unrealizedPl = p.unrealized_pl?.toDoubleOrNull() ?: 0.0
        )
    }

    /** Splits positions into option spreads (grouped by underlying + expiry) and equities. */
    fun classify(positions: List<AlpacaPosition>): Pair<List<Spread>, List<EquityPosition>> {
        val legs = mutableListOf<OptionLeg>()
        val equities = mutableListOf<EquityPosition>()

        for (p in positions) {
            val isOption = p.asset_class == "us_option" || parseOptionLeg(p) != null
            if (isOption) {
                parseOptionLeg(p)?.let { legs += it }
            } else {
                equities += EquityPosition(
                    symbol = p.symbol,
                    qty = p.qty?.toDoubleOrNull() ?: 0.0,
                    marketValue = p.market_value?.toDoubleOrNull() ?: 0.0,
                    currentPrice = p.current_price?.toDoubleOrNull() ?: 0.0,
                    unrealizedPl = p.unrealized_pl?.toDoubleOrNull() ?: 0.0
                )
            }
        }

        val spreads = legs
            .groupBy { it.underlying to it.expiry }
            .map { (key, groupLegs) ->
                Spread(
                    underlying = key.first,
                    expiry = key.second,
                    legs = groupLegs.sortedBy { it.strike }
                )
            }
            .sortedBy { it.expiry }

        return spreads to equities
    }
}
