"""
SwarmContext — the read-only snapshot the orchestrator builds once per cycle and
hands to every sub-agent. Building it once means all three agents reason over the
*same* market picture (important for the orchestrator's fusion step to be fair),
and it keeps broker/API calls bounded per cycle instead of per agent.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Optional

import alpaca as broker  # type: ignore  # sibling module under server/

# Universe the swarm reasons about. Mirrors the broker's UNDERLYINGS so live
# quotes line up with what the radar can actually trade.
UNIVERSE = ["AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "SPY", "QQQ"]


@dataclass
class SwarmContext:
    capital: int = 1000
    live: bool = False                       # broker configured (paper or real)?
    account: Optional[dict] = None           # broker.get_account() or None
    positions: list[dict] = field(default_factory=list)
    quotes: dict[str, float] = field(default_factory=dict)  # ticker → last price
    gemini_key: Optional[str] = None         # for the Vibe agent's LLM pass

    @property
    def mode(self) -> str:
        if not self.live:
            return "MOCK"
        return "LIVE" if broker.is_live() else "PAPER"


def build_context(capital: int = 1000) -> SwarmContext:
    """Assemble a fresh SwarmContext. Degrades gracefully to synthetic data when
    the broker isn't configured (mock mode) so the swarm always has something to
    chew on during development."""
    live = broker.is_configured()

    account = broker.get_account() if live else None
    positions = broker.list_positions() if live else _mock_positions()
    quotes = broker.get_stock_quotes(UNIVERSE) if live else _mock_quotes()

    return SwarmContext(
        capital=capital,
        live=live,
        account=account or _mock_account(),
        positions=positions or [],
        quotes=quotes or _mock_quotes(),
        gemini_key=os.getenv("GEMINI_API_KEY"),
    )


# ── Mock fallbacks (dev only, mirror main.mock_* shapes) ─────────────────────────

def _mock_account() -> dict:
    return {
        "mode": "MOCK", "equity": 50_000.0, "lastEquity": 49_400.0,
        "cash": 31_000.0, "buyingPower": 100_000.0, "portfolioValue": 50_000.0,
        "longMarketValue": 19_000.0, "shortMarketValue": 0.0,
        "daytradeCount": 0, "status": "ACTIVE", "currency": "USD",
    }


def _mock_quotes() -> dict[str, float]:
    return {
        "AAPL": 288.42, "MSFT": 439.10, "GOOGL": 178.30, "AMZN": 201.65,
        "NVDA": 127.05, "SPY": 598.20, "QQQ": 521.40,
    }


def _mock_positions() -> list[dict]:
    return [
        dict(id="POS-AAPL-1", ticker="AAPL", strategyLabel="$265 / $260 Put Spread",
             shortStrike=265.0, underlyingPrice=286.10, entryPremium=78.0,
             currentPremium=38.0, expiration="2026-06-19", contracts=4),
        dict(id="POS-NVDA-1", ticker="NVDA", strategyLabel="$115 Cash-Secured Put",
             shortStrike=115.0, underlyingPrice=124.60, entryPremium=108.0,
             currentPremium=61.0, expiration="2026-06-26", contracts=1),
    ]
