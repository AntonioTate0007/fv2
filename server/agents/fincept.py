"""
FinceptAgent — the market-data / analytics terminal (inspired by Fincept Terminal).

This is the swarm's quant backbone. It computes plain, auditable technical readings
(trend via SMA, momentum via rate-of-change, an RSI-style overbought/oversold gauge)
and a market-breadth summary. Its signals are context, not trade tickets: the
orchestrator uses Fincept's regime read to confirm or veto the Vibe agent's ideas.
"""

from __future__ import annotations

from .base import AgentReport, AgentStatus, BaseAgent, Signal, SignalDirection
from .context import SwarmContext

import alpaca as broker  # type: ignore


def _sma(series: list[float], n: int) -> float:
    if not series:
        return 0.0
    window = series[-n:]
    return sum(window) / len(window)


def _rsi(series: list[float], period: int = 14) -> float:
    """Classic Wilder RSI. Returns 50 (neutral) when there isn't enough history."""
    if len(series) < period + 1:
        return 50.0
    gains, losses = 0.0, 0.0
    for i in range(-period, 0):
        delta = series[i] - series[i - 1]
        if delta >= 0:
            gains += delta
        else:
            losses -= delta
    if losses == 0:
        return 100.0
    rs = (gains / period) / (losses / period)
    return round(100 - (100 / (1 + rs)), 1)


def _synthetic_series(spot: float, ticker: str) -> list[float]:
    """Deterministic 30-bar series anchored on spot, for mock/degraded mode. Stable
    per ticker so analytics don't flicker between cycles."""
    seed = sum(ord(c) for c in ticker)
    out = []
    price = spot * 0.95
    for i in range(30):
        drift = ((seed + i * 7) % 11 - 5) / 1000.0  # ±0.5% deterministic wiggle
        price = price * (1 + drift) + (spot - price) * 0.06  # mean-revert to spot
        out.append(round(price, 2))
    return out


class FinceptAgent(BaseAgent):
    name = "fincept"
    title = "Fincept Terminal"

    def analyze(self, ctx: SwarmContext) -> AgentReport:
        signals: list[Signal] = []
        rows: list[dict] = []
        bullish_breadth = 0

        for ticker, spot in ctx.quotes.items():
            if spot <= 0:
                continue
            series = broker.get_stock_bars(ticker, days=30) if ctx.live else []
            if len(series) < 15:
                series = _synthetic_series(spot, ticker)

            sma10 = _sma(series, 10)
            sma20 = _sma(series, 20)
            rsi = _rsi(series)
            roc = (series[-1] - series[-10]) / series[-10] if len(series) >= 10 and series[-10] else 0.0

            trend = "UP" if sma10 > sma20 else "DOWN"
            if trend == "UP":
                bullish_breadth += 1

            rows.append({
                "ticker": ticker, "spot": round(spot, 2),
                "sma10": round(sma10, 2), "sma20": round(sma20, 2),
                "rsi": rsi, "rocPct": round(roc * 100, 2), "trend": trend,
            })

            # Emit a directional context signal only on a clear regime read.
            if rsi < 30 and trend == "UP":
                signals.append(Signal(
                    ticker=ticker, direction=SignalDirection.BULLISH,
                    conviction=0.7,
                    rationale=f"{ticker} oversold (RSI {rsi}) inside an uptrend — dip in a bull leg.",
                    strategy="PUT_CREDIT_SPREAD"))
            elif rsi > 70 and trend == "DOWN":
                signals.append(Signal(
                    ticker=ticker, direction=SignalDirection.BEARISH,
                    conviction=0.7,
                    rationale=f"{ticker} overbought (RSI {rsi}) inside a downtrend — fade the pop.",
                    strategy="CALL_CREDIT_SPREAD"))

        n = len(rows) or 1
        breadth_pct = bullish_breadth / n
        if breadth_pct >= 0.6:
            regime = "RISK_ON"
        elif breadth_pct <= 0.4:
            regime = "RISK_OFF"
        else:
            regime = "MIXED"

        status = AgentStatus.OK if ctx.live else AgentStatus.DEGRADED
        summary = (f"Market breadth {bullish_breadth}/{n} names trending up → {regime} regime. "
                   f"{len(signals)} technical setups flagged.")

        return AgentReport(
            agent=self.name,
            title=self.title,
            status=status,
            summary=summary,
            signals=signals,
            metrics={
                "regime": regime,
                "breadthPct": round(breadth_pct, 2),
                "namesUp": bullish_breadth,
                "namesTotal": n,
                "table": rows,
            },
        )
