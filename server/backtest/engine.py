"""
Backtest engine for the Fortress put-credit-spread strategy.

It replays a daily price series and trades the bot's actual rules:

  • Open a put credit spread roughly every `entry_every` trading days (one open
    position per symbol at a time).
  • Short strike ≈ `otm_pct` below spot; long strike `width` dollars lower.
  • Target ≈ `dte` trading days to expiry.
  • IV estimated from trailing realized volatility (conservative — see pricing.py).
  • Size each trade so max loss ≈ `capital_per_trade`.
  • Manage daily: take profit at `profit_take` of the credit; otherwise hold to
    expiry and settle at intrinsic value.

Everything is computed — no numbers are invented. The one modeled input is the
option premium (Black-Scholes on realized-vol IV), which is clearly flagged in the
report. P/L is gross of commissions and assumes mid-price fills.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from .data import Bar
from .pricing import put_credit_spread_value


@dataclass
class Trade:
    symbol: str
    entry_date: str
    exit_date: str
    spot_entry: float
    spot_exit: float
    short_k: float
    long_k: float
    width: float
    credit: float          # per share
    contracts: int
    pnl: float             # dollars, net of nothing (gross)
    outcome: str           # "profit_target" | "expired_win" | "expired_loss"


@dataclass
class BacktestConfig:
    otm_pct: float = 0.05
    width: float = 5.0
    dte: int = 10                 # trading days to expiry (~2 weeks)
    entry_every: int = 5          # cadence in trading days (~weekly)
    profit_take: float = 0.50
    capital_per_trade: float = 1000.0
    vol_lookback: int = 20
    risk_free: float = 0.01
    starting_equity: float = 25000.0
    # Implied vol typically trades ABOVE realized vol (the "volatility risk premium"
    # that option sellers harvest). 1.0 = price premiums off pure realized vol
    # (most conservative); ~1.2–1.3 approximates the real IV/RV ratio for large caps.
    iv_premium: float = 1.0


@dataclass
class BacktestResult:
    symbol: str
    config: BacktestConfig
    trades: list[Trade] = field(default_factory=list)
    equity_curve: list[tuple[str, float]] = field(default_factory=list)  # (date, equity)
    start_date: str = ""
    end_date: str = ""

    # ── Derived metrics ──────────────────────────────────────────────────────────
    @property
    def n_trades(self) -> int:
        return len(self.trades)

    @property
    def wins(self) -> int:
        return sum(1 for t in self.trades if t.pnl > 0)

    @property
    def win_rate(self) -> float:
        return self.wins / self.n_trades if self.n_trades else 0.0

    @property
    def total_pnl(self) -> float:
        return sum(t.pnl for t in self.trades)

    @property
    def avg_pnl(self) -> float:
        return self.total_pnl / self.n_trades if self.n_trades else 0.0

    @property
    def final_equity(self) -> float:
        return self.equity_curve[-1][1] if self.equity_curve else self.config.starting_equity

    @property
    def return_pct(self) -> float:
        s = self.config.starting_equity
        return (self.final_equity - s) / s if s else 0.0

    @property
    def max_drawdown(self) -> float:
        peak = -math.inf
        mdd = 0.0
        for _, eq in self.equity_curve:
            peak = max(peak, eq)
            if peak > 0:
                mdd = min(mdd, (eq - peak) / peak)
        return mdd

    @property
    def avg_win(self) -> float:
        w = [t.pnl for t in self.trades if t.pnl > 0]
        return sum(w) / len(w) if w else 0.0

    @property
    def avg_loss(self) -> float:
        l = [t.pnl for t in self.trades if t.pnl <= 0]
        return sum(l) / len(l) if l else 0.0


def _realized_vol(bars: list[Bar], i: int, lookback: int) -> float:
    """Annualized realized volatility from log returns over the trailing window."""
    lo = max(1, i - lookback + 1)
    rets = []
    for j in range(lo, i + 1):
        p0, p1 = bars[j - 1].close, bars[j].close
        if p0 > 0 and p1 > 0:
            rets.append(math.log(p1 / p0))
    if len(rets) < 2:
        return 0.30  # sane default until we have history
    mean = sum(rets) / len(rets)
    var = sum((r - mean) ** 2 for r in rets) / (len(rets) - 1)
    return max(0.05, math.sqrt(var) * math.sqrt(252.0))


def run_backtest(symbol: str, bars: list[Bar],
                 cfg: BacktestConfig | None = None) -> BacktestResult:
    cfg = cfg or BacktestConfig()
    res = BacktestResult(symbol=symbol, config=cfg)
    if len(bars) < cfg.vol_lookback + cfg.dte + 2:
        return res

    res.start_date, res.end_date = bars[0].date, bars[-1].date
    equity = cfg.starting_equity
    open_pos: dict | None = None
    last_entry_idx = -10_000

    for i in range(len(bars)):
        bar = bars[i]

        # ── Manage an open position ──────────────────────────────────────────────
        if open_pos is not None:
            days_left = open_pos["expiry_idx"] - i
            t_years = max(0.0, days_left) / 252.0
            sigma = open_pos["sigma"]
            spread_val = put_credit_spread_value(
                bar.close, open_pos["short_k"], open_pos["long_k"],
                t_years, sigma, cfg.risk_free)
            credit = open_pos["credit"]
            captured = (credit - spread_val) / credit if credit > 0 else 0.0

            close_now, outcome = False, ""
            if captured >= cfg.profit_take:
                close_now, outcome, exit_val = True, "profit_target", spread_val
            elif days_left <= 0:
                # Settle at intrinsic value.
                intrinsic = (max(open_pos["short_k"] - bar.close, 0.0)
                             - max(open_pos["long_k"] - bar.close, 0.0))
                exit_val = min(intrinsic, open_pos["width"])
                outcome = "expired_win" if exit_val < credit else "expired_loss"
                close_now = True

            if close_now:
                pnl = (credit - exit_val) * 100.0 * open_pos["contracts"]
                equity += pnl
                res.trades.append(Trade(
                    symbol=symbol, entry_date=open_pos["entry_date"], exit_date=bar.date,
                    spot_entry=open_pos["spot_entry"], spot_exit=bar.close,
                    short_k=open_pos["short_k"], long_k=open_pos["long_k"],
                    width=open_pos["width"], credit=credit, contracts=open_pos["contracts"],
                    pnl=pnl, outcome=outcome))
                open_pos = None

        # ── Maybe open a new position ────────────────────────────────────────────
        room_to_expire = i + cfg.dte < len(bars)
        if (open_pos is None and room_to_expire
                and i - last_entry_idx >= cfg.entry_every
                and i >= cfg.vol_lookback):
            spot = bar.close
            short_k = round(spot * (1 - cfg.otm_pct))
            long_k = short_k - cfg.width
            if long_k > 0:
                sigma = _realized_vol(bars, i, cfg.vol_lookback) * cfg.iv_premium
                t_years = cfg.dte / 252.0
                credit = put_credit_spread_value(spot, short_k, long_k, t_years,
                                                  sigma, cfg.risk_free)
                max_loss_per_contract = (cfg.width - credit) * 100.0
                if credit > 0.02 and max_loss_per_contract > 0:
                    contracts = max(1, int(cfg.capital_per_trade // max_loss_per_contract))
                    open_pos = {
                        "entry_date": bar.date, "spot_entry": spot,
                        "short_k": short_k, "long_k": long_k, "width": cfg.width,
                        "credit": credit, "contracts": contracts, "sigma": sigma,
                        "expiry_idx": i + cfg.dte,
                    }
                    last_entry_idx = i

        res.equity_curve.append((bar.date, equity))

    return res
