"""
Portfolio backtest — run the strategy across a basket of symbols on one shared
account, so we measure account-level return, win rate, and drawdown rather than a
single name in isolation.

Aggregation is deliberately simple and honest: each symbol is backtested
independently (one open position per symbol at a time, sized to `capital_per_trade`
max loss), then every trade across all symbols is merged on its exit date and
applied to one shared equity curve. Concurrent risk is bounded by the per-trade
sizing × number of symbols.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .data import Bar
from .engine import BacktestConfig, Trade, run_backtest


@dataclass
class PortfolioResult:
    symbols: list[str]
    config: BacktestConfig
    trades: list[Trade] = field(default_factory=list)            # merged, sorted by exit
    equity_curve: list[tuple[str, float]] = field(default_factory=list)
    per_symbol: dict[str, dict] = field(default_factory=dict)
    start_date: str = ""
    end_date: str = ""

    @property
    def n_trades(self) -> int: return len(self.trades)
    @property
    def wins(self) -> int: return sum(1 for t in self.trades if t.pnl > 0)
    @property
    def win_rate(self) -> float: return self.wins / self.n_trades if self.n_trades else 0.0
    @property
    def total_pnl(self) -> float: return sum(t.pnl for t in self.trades)
    @property
    def final_equity(self) -> float:
        return self.equity_curve[-1][1] if self.equity_curve else self.config.starting_equity
    @property
    def return_pct(self) -> float:
        s = self.config.starting_equity
        return (self.final_equity - s) / s if s else 0.0

    @property
    def max_drawdown(self) -> float:
        peak, mdd = float("-inf"), 0.0
        for _, eq in self.equity_curve:
            peak = max(peak, eq)
            if peak > 0:
                mdd = min(mdd, (eq - peak) / peak)
        return mdd

    @property
    def worst_trade(self) -> float:
        return min((t.pnl for t in self.trades), default=0.0)


def run_portfolio(data_by_symbol: dict[str, list[Bar]],
                  cfg: BacktestConfig | None = None) -> PortfolioResult:
    cfg = cfg or BacktestConfig()
    symbols = sorted(data_by_symbol)
    res = PortfolioResult(symbols=symbols, config=cfg)

    all_trades: list[Trade] = []
    for sym in symbols:
        sub = run_backtest(sym, data_by_symbol[sym], cfg)
        all_trades.extend(sub.trades)
        res.per_symbol[sym] = {
            "trades": sub.n_trades, "win_rate": sub.win_rate,
            "total_pnl": sub.total_pnl, "return_pct": sub.return_pct,
        }

    all_trades.sort(key=lambda t: (t.exit_date, t.entry_date))
    res.trades = all_trades
    if all_trades:
        res.start_date = min(t.entry_date for t in all_trades)
        res.end_date = max(t.exit_date for t in all_trades)

    equity = cfg.starting_equity
    curve = [(res.start_date or "start", equity)]
    for t in all_trades:
        equity += t.pnl
        curve.append((t.exit_date, equity))
    res.equity_curve = curve
    return res
