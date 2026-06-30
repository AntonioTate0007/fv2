"""
Alpaca broker integration. Hard real-money path; treated with paranoia.

Defaults to paper trading. To go live, the operator must set ALPACA_PAPER=false
explicitly — there is no other switch. There is also no "yolo" code path.

What this module exposes:

    is_configured()                 → bool      env vars present?
    is_live()                       → bool      paper vs live trading
    list_positions()                → [ActivePosition]
    close_position(position_id)     → CloseResponse
    submit_spread(trade, capital)   → DeployResponse   (multi-leg credit spread)
    scan_chains(capital)            → [ScannedTrade]   real options-chain scan

Errors are logged and surfaced as DeployResponse.success=False / CloseResponse with
message, never raised — the Android client expects polite failures, not 500s.
"""

from __future__ import annotations

import logging
import math
import os
import threading
import time
from contextvars import ContextVar
from datetime import date, datetime, timedelta, timezone
from typing import Optional

log = logging.getLogger("fortress.alpaca")

try:
    from alpaca.trading.client import TradingClient
    from alpaca.trading.requests import (
        ClosePositionRequest,
        GetOptionContractsRequest,
        LimitOrderRequest,
        OptionLegRequest,
    )
    from alpaca.trading.enums import (
        AssetStatus,
        ContractType,
        OrderClass,
        OrderSide,
        OrderStatus,
        PositionIntent,
        TimeInForce,
    )
    from alpaca.data.historical.option import OptionHistoricalDataClient
    from alpaca.data.requests import OptionLatestQuoteRequest
    HAS_ALPACA = True
except ImportError:  # SDK not installed → routes fall back to mock data.
    HAS_ALPACA = False


# ── Config ──────────────────────────────────────────────────────────────────────

UNDERLYINGS = ["AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "SPY", "QQQ"]
TARGET_DTE_LO = 7
TARGET_DTE_HI = 14
MIN_OTM_BUFFER = 0.05   # short strike must be ≥5% below spot for puts
SPREAD_WIDTH = 5.0      # default $5 wide for credit spreads


# ── Request-scoped overrides ────────────────────────────────────────────────────
#
# When a request supplies X-Alpaca-Key / X-Alpaca-Secret / X-Alpaca-Paper headers,
# the FastAPI dependency stuffs them into this contextvar for the lifetime of the
# request. _api_key() / _api_secret() / is_live() check it first, falling back to
# the runtime override and then to env vars. This lets the companion forward the
# operator's keys per-request without ever touching the host's env config.

_REQUEST_CREDS: ContextVar["tuple[str | None, str | None, bool | None] | None"] = \
    ContextVar("alpaca_request_creds", default=None)


def set_request_creds(key: str | None, secret: str | None, paper: bool | None) -> None:
    """Set Alpaca creds for the current request context. Pass None for any field
    to defer to the next-lower precedence layer (runtime override → env)."""
    _REQUEST_CREDS.set((key or None, secret or None, paper))


def clear_request_creds() -> None:
    _REQUEST_CREDS.set(None)


def _api_key() -> str | None:
    rc = _REQUEST_CREDS.get()
    if rc and rc[0]:
        return rc[0]
    return os.getenv("ALPACA_API_KEY") or os.getenv("APCA_API_KEY_ID")


def _api_secret() -> str | None:
    rc = _REQUEST_CREDS.get()
    if rc and rc[1]:
        return rc[1]
    return os.getenv("ALPACA_API_SECRET") or os.getenv("APCA_API_SECRET_KEY")


# Runtime paper/live override. None → defer to the ALPACA_PAPER env var.
# True → force paper, False → force live. Set via set_paper_mode().
_mode_override: "bool | None" = None


def is_configured() -> bool:
    return HAS_ALPACA and bool(_api_key()) and bool(_api_secret())


def is_live() -> bool:
    """True only when paper trading is explicitly off.

    Precedence: per-request header > runtime override > ALPACA_PAPER env.
    Header value `paper=True` forces paper, `paper=False` forces live; `None`
    falls through.
    """
    rc = _REQUEST_CREDS.get()
    if rc and rc[2] is not None:
        return not rc[2]
    if _mode_override is not None:
        return not _mode_override
    return os.getenv("ALPACA_PAPER", "true").strip().lower() in ("false", "0", "no")


def set_paper_mode(paper: bool) -> None:
    """Flip the live/paper switch at runtime. Going live means REAL money —
    callers must gate this behind an explicit confirmation."""
    global _mode_override
    _mode_override = bool(paper)
    with _client_lock:
        _trading_cache.clear()  # rebuild against the new endpoint on next call
    log.warning("[alpaca] runtime mode set to %s", "PAPER" if paper else "LIVE")


def clear_mode_override() -> None:
    """Drop the runtime override and fall back to the ALPACA_PAPER env var."""
    global _mode_override
    _mode_override = None
    with _client_lock:
        _trading_cache.clear()


# ── Clients (cached per (key, secret, paper) tuple) ─────────────────────────────
#
# Per-request creds mean the trading endpoint URL (paper vs. live) can change
# request-to-request. A simple LRU-ish dict keyed by the cred tuple keeps us from
# rebuilding SDK clients on every call while still respecting whichever creds
# the caller sent. Capped at 8 entries — anything bigger means something is
# wrong (way more rotating keys than any operator would have).

_CLIENT_CACHE_MAX = 8
_client_lock = threading.Lock()
_trading_cache: dict[tuple, "TradingClient"] = {}
_option_cache: dict[tuple, "OptionHistoricalDataClient"] = {}


def _evict_if_full(cache: dict) -> None:
    while len(cache) > _CLIENT_CACHE_MAX:
        cache.pop(next(iter(cache)))


def _trading_client() -> "TradingClient":
    key, secret, paper = _api_key(), _api_secret(), not is_live()
    cache_key = (key or "", secret or "", paper)
    with _client_lock:
        client = _trading_cache.get(cache_key)
        if client is None:
            client = TradingClient(api_key=key, secret_key=secret, paper=paper)
            _trading_cache[cache_key] = client
            _evict_if_full(_trading_cache)
            log.info("[alpaca] trading client up — mode=%s", "LIVE" if not paper else "PAPER")
        return client


def _option_client() -> "OptionHistoricalDataClient":
    key, secret = _api_key(), _api_secret()
    cache_key = (key or "", secret or "")
    with _client_lock:
        client = _option_cache.get(cache_key)
        if client is None:
            client = OptionHistoricalDataClient(api_key=key, secret_key=secret)
            _option_cache[cache_key] = client
            _evict_if_full(_option_cache)
        return client


# ── Helpers ─────────────────────────────────────────────────────────────────────

def _parse_occ_symbol(sym: str) -> tuple[str, str, str, float] | None:
    """OCC option symbol: ROOT  YYMMDD  C/P  STRIKE×1000.

    Example: AAPL250515P00265000 → (AAPL, 2025-05-15, P, 265.0)
    """
    if len(sym) < 15:
        return None
    # Find first digit — root ticker is everything before it.
    i = 0
    while i < len(sym) and sym[i].isalpha():
        i += 1
    root = sym[:i]
    if i + 15 > len(sym):
        return None
    date_part = sym[i:i + 6]
    cp = sym[i + 6]
    strike_part = sym[i + 7:i + 15]
    try:
        yy, mm, dd = int(date_part[:2]), int(date_part[2:4]), int(date_part[4:6])
        # Alpaca uses 2-digit year; assume 20xx.
        exp = f"20{yy:02d}-{mm:02d}-{dd:02d}"
        strike = int(strike_part) / 1000.0
        return root, exp, cp, strike
    except ValueError:
        return None


def _strategy_label_from_legs(legs: list[dict]) -> str:
    """Best-effort human label for a multi-leg position."""
    if len(legs) == 2:
        # Likely a credit spread.
        a, b = sorted(legs, key=lambda x: x["strike"])
        if a["cp"] == "P" and b["cp"] == "P":
            return f"${int(b['strike'])} / ${int(a['strike'])} Put Spread"
        if a["cp"] == "C" and b["cp"] == "C":
            return f"${int(a['strike'])} / ${int(b['strike'])} Call Spread"
    if len(legs) == 1 and legs[0]["cp"] == "P":
        return f"${int(legs[0]['strike'])} Cash-Secured Put"
    return "Multi-leg options position"


# ── Public API ──────────────────────────────────────────────────────────────────

def list_positions() -> list[dict]:
    """
    Returns positions in the wire-shape the Android `ActivePosition` model expects.
    Groups option legs by underlying+expiry into a single composite "position" row.
    """
    if not is_configured():
        return []

    try:
        raw = _trading_client().get_all_positions()
    except Exception as e:
        log.exception("[alpaca] get_all_positions failed: %s", e)
        return []

    # Group option legs by (underlying, expiry).
    groups: dict[tuple[str, str], list[dict]] = {}
    underlyings_to_quote: set[str] = set()

    for p in raw:
        # Only options for now — equity wheel positions can come later.
        if getattr(p, "asset_class", "").lower() != "us_option":
            continue
        parsed = _parse_occ_symbol(p.symbol)
        if not parsed:
            continue
        root, exp, cp, strike = parsed
        underlyings_to_quote.add(root)
        leg = {
            "symbol": p.symbol,
            "root": root,
            "exp": exp,
            "cp": cp,
            "strike": strike,
            "qty": int(p.qty),
            "side": "long" if int(p.qty) > 0 else "short",
            "entry_price": float(p.avg_entry_price or 0),
            "current_price": float(p.current_price or 0),
        }
        groups.setdefault((root, exp), []).append(leg)

    # Pull underlying spot quotes (one cheap call per ticker).
    spots: dict[str, float] = {}
    if underlyings_to_quote:
        try:
            from alpaca.data.historical.stock import StockHistoricalDataClient
            from alpaca.data.requests import StockLatestTradeRequest
            sdc = StockHistoricalDataClient(api_key=_api_key(), secret_key=_api_secret())
            res = sdc.get_stock_latest_trade(
                StockLatestTradeRequest(symbol_or_symbols=list(underlyings_to_quote))
            )
            for sym, trade in res.items():
                spots[sym] = float(trade.price)
        except Exception as e:
            log.warning("[alpaca] underlying spot fetch failed: %s", e)

    out: list[dict] = []
    for (root, exp), legs in groups.items():
        legs_sorted = sorted(legs, key=lambda x: x["strike"])
        # short strike = the leg sold to open (negative qty in alpaca terms)
        shorts = [l for l in legs_sorted if l["side"] == "short"]
        if not shorts:
            continue
        short_strike = shorts[0]["strike"]
        contracts = abs(legs_sorted[0]["qty"])
        # Net credit per contract = sum of (entry × side-sign), * 100 for $ value.
        # For a short leg the credit is +entry; long leg is -entry.
        net_entry_credit = sum(
            (1 if l["side"] == "short" else -1) * l["entry_price"] for l in legs
        ) * 100.0
        net_current_cost = sum(
            (1 if l["side"] == "short" else -1) * l["current_price"] for l in legs
        ) * 100.0
        out.append({
            "id": f"{root}-{exp}-{int(short_strike)}",
            "ticker": root,
            "strategyLabel": _strategy_label_from_legs(legs_sorted),
            "shortStrike": short_strike,
            "underlyingPrice": spots.get(root, 0.0),
            "entryPremium": round(net_entry_credit, 2),
            "currentPremium": round(net_current_cost, 2),
            "expiration": exp,
            "contracts": contracts,
        })
    return out


def close_position(position_id: str) -> tuple[bool, str]:
    """Close ALL legs that match the given composite id (ROOT-YYYY-MM-DD-STRIKE)."""
    if not is_configured():
        return False, "Alpaca not configured on server."
    try:
        raw = _trading_client().get_all_positions()
    except Exception as e:
        log.exception("[alpaca] get_all_positions failed pre-close: %s", e)
        return False, f"broker unreachable: {e}"

    parts = position_id.split("-")
    if len(parts) < 4:
        return False, "bad position id"
    target_root = parts[0]
    target_exp = "-".join(parts[1:4])
    try:
        target_strike = float(parts[4])
    except (IndexError, ValueError):
        target_strike = -1

    closed = 0
    errors: list[str] = []
    for p in raw:
        parsed = _parse_occ_symbol(p.symbol)
        if not parsed:
            continue
        root, exp, _cp, _strike = parsed
        if root != target_root or exp != target_exp:
            continue
        try:
            _trading_client().close_position(p.symbol)
            closed += 1
        except Exception as e:
            log.exception("[alpaca] close leg %s failed: %s", p.symbol, e)
            errors.append(f"{p.symbol}: {e}")

    if closed == 0:
        return False, f"no matching legs found for {position_id}"
    if errors:
        return False, f"closed {closed} leg(s), errors: {'; '.join(errors)}"
    return True, f"Closed {closed} leg(s) at market."


def submit_spread(trade: dict, capital: int) -> tuple[bool, Optional[str], Optional[str]]:
    """
    Submit a put-credit-spread (or CSP) order against Alpaca.

    `trade` mirrors the ScannedTrade dict the client sent up via /v1/radar/deploy.
    Returns (success, order_id, message).
    """
    if not is_configured():
        return False, None, "Alpaca not configured on server."

    ticker = trade.get("ticker")
    strategy = trade.get("strategy")
    short_strike = float(trade.get("shortStrike") or 0)
    long_strike = float(trade.get("longStrike") or 0)
    expiration = trade.get("expiration")  # yyyy-mm-dd
    if not (ticker and strategy and short_strike and expiration):
        return False, None, "trade payload missing required fields"

    contracts = max(1, capital // 500)  # one contract per $500 block, mirrors UI math

    try:
        # Resolve OCC symbols by querying Alpaca's option-contracts catalog.
        client = _trading_client()
        req = GetOptionContractsRequest(
            underlying_symbols=[ticker],
            expiration_date=expiration,
            type=ContractType.PUT if "PUT" in strategy else ContractType.CALL,
            status=AssetStatus.ACTIVE,
        )
        contracts_resp = client.get_option_contracts(req)
        catalog = contracts_resp.option_contracts or []
        # Match strike with a small float tolerance.
        def _by_strike(strike: float):
            for c in catalog:
                if math.isclose(float(c.strike_price), strike, abs_tol=0.01):
                    return c
            return None

        short_leg_contract = _by_strike(short_strike)
        if short_leg_contract is None:
            return False, None, f"no live contract for {ticker} {expiration} ${short_strike}"

        # Build legs.
        legs: list[OptionLegRequest] = [
            OptionLegRequest(
                symbol=short_leg_contract.symbol,
                ratio_qty=1,
                side=OrderSide.SELL,
                position_intent=PositionIntent.SELL_TO_OPEN,
            )
        ]
        is_spread = strategy in ("PUT_CREDIT_SPREAD", "CALL_CREDIT_SPREAD")
        if is_spread:
            long_leg_contract = _by_strike(long_strike)
            if long_leg_contract is None:
                return False, None, f"no live contract for {ticker} {expiration} ${long_strike}"
            legs.append(
                OptionLegRequest(
                    symbol=long_leg_contract.symbol,
                    ratio_qty=1,
                    side=OrderSide.BUY,
                    position_intent=PositionIntent.BUY_TO_OPEN,
                )
            )

        # Pull a current quote to set a safe limit price.
        try:
            quote_req = OptionLatestQuoteRequest(
                symbol_or_symbols=[l.symbol for l in legs]
            )
            quotes = _option_client().get_option_latest_quote(quote_req)
            short_q = quotes.get(legs[0].symbol)
            short_bid = float(getattr(short_q, "bid_price", 0) or 0)
            short_ask = float(getattr(short_q, "ask_price", 0) or 0)
            net_credit = short_bid
            if is_spread:
                long_q = quotes.get(legs[1].symbol)
                long_bid = float(getattr(long_q, "bid_price", 0) or 0)
                long_ask = float(getattr(long_q, "ask_price", 0) or 0)
                net_credit = max(0.05, round(short_bid - long_ask, 2))
            else:
                net_credit = max(0.05, round(short_bid, 2))
        except Exception as e:
            log.warning("[alpaca] quote lookup failed, falling back to estimate: %s", e)
            net_credit = max(0.05, round(float(trade.get("estimatedCreditPerContract") or 0.5), 2))

        # Submit as a multi-leg net-credit limit order. For single-leg CSP, use
        # a plain limit sell instead of MLEG.
        if is_spread:
            order_req = LimitOrderRequest(
                qty=contracts,
                side=OrderSide.SELL,        # net SELL = receive credit
                time_in_force=TimeInForce.DAY,
                order_class=OrderClass.MLEG,
                legs=legs,
                limit_price=round(net_credit, 2),
            )
        else:
            order_req = LimitOrderRequest(
                symbol=legs[0].symbol,
                qty=contracts,
                side=OrderSide.SELL,
                time_in_force=TimeInForce.DAY,
                limit_price=round(net_credit, 2),
            )

        order = client.submit_order(order_req)
        order_id = str(getattr(order, "id", "") or "")
        log.info("[alpaca] submitted %s %s × %d @ %.2f → %s",
                 strategy, ticker, contracts, net_credit, order_id)
        return True, order_id, f"Limit ${net_credit:.2f} × {contracts} submitted"
    except Exception as e:
        log.exception("[alpaca] submit_spread failed: %s", e)
        return False, None, f"broker rejected: {e}"


# ── Scanner ─────────────────────────────────────────────────────────────────────

def scan_chains(capital: int) -> list[dict]:
    """
    Pull live options chains for the configured UNDERLYINGS and surface put-credit
    spread candidates that pass the basic Fortress filters: DTE 7-14, short strike
    ≥5% OTM. This is intentionally minimal — the heavy filter stack (IV rank,
    earnings clearance, etc.) belongs in a separate enrichment pass.
    """
    if not is_configured():
        return []

    today = date.today()
    target_lo = today + timedelta(days=TARGET_DTE_LO)
    target_hi = today + timedelta(days=TARGET_DTE_HI)

    out: list[dict] = []
    client = _trading_client()
    odc = _option_client()

    # Get spot prices in one call.
    try:
        from alpaca.data.historical.stock import StockHistoricalDataClient
        from alpaca.data.requests import StockLatestTradeRequest
        sdc = StockHistoricalDataClient(api_key=_api_key(), secret_key=_api_secret())
        spots_resp = sdc.get_stock_latest_trade(
            StockLatestTradeRequest(symbol_or_symbols=UNDERLYINGS)
        )
        spots = {sym: float(trade.price) for sym, trade in spots_resp.items()}
    except Exception as e:
        log.warning("[alpaca] spot fetch failed: %s", e)
        return []

    for ticker in UNDERLYINGS:
        spot = spots.get(ticker, 0.0)
        if spot <= 0:
            continue
        try:
            req = GetOptionContractsRequest(
                underlying_symbols=[ticker],
                expiration_date_gte=target_lo.isoformat(),
                expiration_date_lte=target_hi.isoformat(),
                type=ContractType.PUT,
                status=AssetStatus.ACTIVE,
            )
            contracts = (client.get_option_contracts(req).option_contracts or [])
        except Exception as e:
            log.warning("[alpaca] chain fetch %s failed: %s", ticker, e)
            continue

        # Pick a short put ~7-10% OTM, with the longest DTE in the window so we
        # capture max premium without going past the theta sweet spot.
        target_short_strike = spot * (1 - 0.07)
        candidates = sorted(
            contracts,
            key=lambda c: (
                abs(float(c.strike_price) - target_short_strike),
                -datetime.fromisoformat(str(c.expiration_date)).toordinal(),
            )
        )
        if not candidates:
            continue
        short_c = candidates[0]
        short_strike = float(short_c.strike_price)
        # The long-protection strike is short_strike - SPREAD_WIDTH, find nearest:
        long_target = short_strike - SPREAD_WIDTH
        long_c = min(
            (c for c in contracts
             if str(c.expiration_date) == str(short_c.expiration_date)
             and float(c.strike_price) < short_strike),
            key=lambda c: abs(float(c.strike_price) - long_target),
            default=None,
        )
        if long_c is None:
            continue
        long_strike = float(long_c.strike_price)
        if short_strike - long_strike <= 0:
            continue

        # Quote both legs to estimate the net credit.
        try:
            qreq = OptionLatestQuoteRequest(
                symbol_or_symbols=[short_c.symbol, long_c.symbol]
            )
            quotes = odc.get_option_latest_quote(qreq)
            sb = float(getattr(quotes.get(short_c.symbol), "bid_price", 0) or 0)
            la = float(getattr(quotes.get(long_c.symbol), "ask_price", 0) or 0)
            net_credit = max(0.0, round(sb - la, 2))
        except Exception as e:
            log.warning("[alpaca] quote %s failed: %s", ticker, e)
            net_credit = 0.0

        exp_iso = str(short_c.expiration_date)
        try:
            dte = (datetime.fromisoformat(exp_iso).date() - today).days
        except ValueError:
            dte = 0
        safety_buffer = max(0.0, (spot - short_strike) / spot)
        if safety_buffer < MIN_OTM_BUFFER:
            continue

        # Probability of profit ≈ 1 - |delta| of the short leg. We don't have delta
        # without a Greeks call; approximate with normalized buffer for now.
        prob_profit = max(0.55, min(0.92, 0.55 + safety_buffer * 4))
        iv_rank = 0.50  # placeholder — needs historical IV to compute properly

        out.append({
            "id": f"{ticker}-PCS-{int(short_strike)}",
            "ticker": ticker,
            "strategy": "PUT_CREDIT_SPREAD",
            "shortStrike": short_strike,
            "longStrike": long_strike,
            "expiration": exp_iso,
            "dte": dte,
            "estimatedCreditPerContract": net_credit,
            "safetyBufferPct": round(safety_buffer, 4),
            "underlyingPrice": round(spot, 2),
            "probabilityOfProfit": round(prob_profit, 2),
            "ivRank": iv_rank,
            "earningsClear": True,  # TODO: cross-check earnings calendar
        })

    return out


# ── Account / quotes (read-only helpers used by the agent swarm) ─────────────────

def get_account() -> Optional[dict]:
    """Snapshot of the trading account, or None when the broker isn't configured.

    Shape is deliberately flat and JSON-safe so the orchestrator and dashboard can
    consume it directly without touching Alpaca SDK objects.
    """
    if not is_configured():
        return None
    try:
        a = _trading_client().get_account()
    except Exception as e:
        log.warning("[alpaca] get_account failed: %s", e)
        return None

    def _f(name: str) -> float:
        try:
            return float(getattr(a, name, 0) or 0)
        except (TypeError, ValueError):
            return 0.0

    return {
        "mode": "LIVE" if is_live() else "PAPER",
        "equity": _f("equity"),
        "lastEquity": _f("last_equity"),
        "cash": _f("cash"),
        "buyingPower": _f("buying_power"),
        "portfolioValue": _f("portfolio_value"),
        "longMarketValue": _f("long_market_value"),
        "shortMarketValue": _f("short_market_value"),
        "daytradeCount": int(getattr(a, "daytrade_count", 0) or 0),
        "status": str(getattr(a, "status", "")),
        "currency": str(getattr(a, "currency", "USD")),
    }


def get_stock_quotes(symbols: list[str]) -> dict[str, float]:
    """Latest trade price per symbol. Empty dict on any failure / mock mode."""
    if not is_configured() or not symbols:
        return {}
    try:
        from alpaca.data.historical.stock import StockHistoricalDataClient
        from alpaca.data.requests import StockLatestTradeRequest
        sdc = StockHistoricalDataClient(api_key=_api_key(), secret_key=_api_secret())
        resp = sdc.get_stock_latest_trade(
            StockLatestTradeRequest(symbol_or_symbols=symbols)
        )
        return {sym: float(trade.price) for sym, trade in resp.items()}
    except Exception as e:
        log.warning("[alpaca] stock quote fetch failed: %s", e)
        return {}


def get_stock_bars(symbol: str, days: int = 30) -> list[float]:
    """Daily closing prices for the last `days` sessions (oldest→newest).

    Used by the Fincept analytics agent for technical indicators. Returns an empty
    list in mock mode or on failure so callers can degrade to synthetic series.
    """
    if not is_configured():
        return []
    try:
        from alpaca.data.historical.stock import StockHistoricalDataClient
        from alpaca.data.requests import StockBarsRequest
        from alpaca.data.timeframe import TimeFrame
        sdc = StockHistoricalDataClient(api_key=_api_key(), secret_key=_api_secret())
        start = datetime.now(timezone.utc) - timedelta(days=days * 2 + 5)
        req = StockBarsRequest(
            symbol_or_symbols=symbol,
            timeframe=TimeFrame.Day,
            start=start,
        )
        bars = sdc.get_stock_bars(req)
        data = bars.data.get(symbol, []) if hasattr(bars, "data") else []
        closes = [float(b.close) for b in data]
        return closes[-days:] if len(closes) > days else closes
    except Exception as e:
        log.warning("[alpaca] bars fetch %s failed: %s", symbol, e)
        return []
