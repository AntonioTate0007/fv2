package com.fortress.trader.data

import kotlinx.serialization.Serializable

/**
 * Subset of Alpaca's `GET /v2/account` response. Alpaca returns numeric fields as
 * JSON strings, so everything is parsed as String and converted on demand.
 */
@Serializable
data class AlpacaAccount(
    val status: String? = null,
    val currency: String? = null,
    val cash: String? = null,
    val portfolio_value: String? = null,
    val equity: String? = null,
    val last_equity: String? = null,
    val buying_power: String? = null
)

/**
 * Subset of an Alpaca position from `GET /v2/positions`. For options the [symbol]
 * is an OCC symbol (e.g. `ORCL250620P00195000`).
 */
@Serializable
data class AlpacaPosition(
    val symbol: String,
    val asset_class: String? = null,
    val qty: String? = null,
    val side: String? = null,
    val avg_entry_price: String? = null,
    val market_value: String? = null,
    val current_price: String? = null,
    val unrealized_pl: String? = null,
    val unrealized_plpc: String? = null
)
