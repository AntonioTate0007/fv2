"""
Backtest runner / CLI.

    python -m backtest.run --csv data/AAPL.csv --symbol AAPL --capital 1000

Loads a daily-price CSV, runs the put-credit-spread strategy, prints a report, and
writes an SVG equity curve next to it. Every printed number is computed from the
run; the only modeled quantity is the option premium (Black-Scholes on realized-vol
IV), which is flagged in the report header.
"""

from __future__ import annotations

import argparse
import os

from .data import load_csv
from .engine import BacktestConfig, BacktestResult, run_backtest


def _fmt_money(x: float) -> str:
    return f"${x:,.0f}"


def render_report(res: BacktestResult) -> str:
    c = res.config
    L = []
    L.append("=" * 66)
    L.append(f"  FORTRESS BACKTEST — {res.symbol}   put credit spreads")
    L.append(f"  {res.start_date} → {res.end_date}   ({len(res.equity_curve)} trading days)")
    L.append("=" * 66)
    L.append("  Strategy:")
    L.append(f"    short strike ~{c.otm_pct*100:.0f}% OTM, ${c.width:.0f}-wide, "
             f"~{c.dte}d to expiry, weekly entries")
    L.append(f"    take profit at {c.profit_take*100:.0f}% of credit, "
             f"size to ~{_fmt_money(c.capital_per_trade)} max loss/trade")
    L.append("-" * 66)
    L.append(f"  Trades:          {res.n_trades}")
    L.append(f"  Win rate:        {res.win_rate*100:.1f}%   ({res.wins}W / {res.n_trades-res.wins}L)")
    L.append(f"  Avg P/L / trade: {_fmt_money(res.avg_pnl)}")
    L.append(f"    avg win:       {_fmt_money(res.avg_win)}")
    L.append(f"    avg loss:      {_fmt_money(res.avg_loss)}")
    L.append(f"  Total P/L:       {_fmt_money(res.total_pnl)}")
    L.append(f"  Start equity:    {_fmt_money(c.starting_equity)}")
    L.append(f"  End equity:      {_fmt_money(res.final_equity)}")
    L.append(f"  Return:          {res.return_pct*100:+.1f}%")
    L.append(f"  Max drawdown:    {res.max_drawdown*100:.1f}%")
    L.append("=" * 66)
    L.append("  NOTE: option premiums are MODELED (Black-Scholes, IV≈realized vol),")
    L.append("  not historical option quotes. Realized<implied vol biases premiums")
    L.append("  LOW, so this is a conservative estimate. Gross of commissions/slippage.")
    L.append("=" * 66)
    return "\n".join(L)


def render_svg(res: BacktestResult, width=900, height=320) -> str:
    pts = res.equity_curve
    if len(pts) < 2:
        return "<svg xmlns='http://www.w3.org/2000/svg'/>"
    eqs = [e for _, e in pts]
    lo, hi = min(eqs), max(eqs)
    rng = (hi - lo) or 1.0
    pad = 50
    w, h = width - 2 * pad, height - 2 * pad

    def x(i): return pad + w * i / (len(pts) - 1)
    def y(e): return pad + h * (1 - (e - lo) / rng)

    poly = " ".join(f"{x(i):.1f},{y(e):.1f}" for i, (_, e) in enumerate(pts))
    start_eq = res.config.starting_equity
    zero_y = y(start_eq) if lo <= start_eq <= hi else None
    up = res.final_equity >= start_eq
    color = "#3fb950" if up else "#f85149"

    parts = [
        f"<svg xmlns='http://www.w3.org/2000/svg' width='{width}' height='{height}' "
        f"viewBox='0 0 {width} {height}' font-family='monospace'>",
        f"<rect width='{width}' height='{height}' fill='#0a0e14'/>",
        f"<text x='{pad}' y='28' fill='#e6edf3' font-size='15'>"
        f"FORTRESS backtest — {res.symbol}  ({res.start_date} → {res.end_date})</text>",
    ]
    if zero_y is not None:
        parts.append(f"<line x1='{pad}' y1='{zero_y:.1f}' x2='{width-pad}' y2='{zero_y:.1f}' "
                     f"stroke='#30363d' stroke-dasharray='4 4'/>")
        parts.append(f"<text x='{width-pad}' y='{zero_y-4:.1f}' fill='#8b98a5' "
                     f"font-size='10' text-anchor='end'>start {_fmt_money(start_eq)}</text>")
    parts.append(f"<polyline points='{poly}' fill='none' stroke='{color}' stroke-width='2'/>")
    parts.append(f"<text x='{pad}' y='{height-16}' fill='#8b98a5' font-size='11'>"
                 f"{res.n_trades} trades · win {res.win_rate*100:.0f}% · "
                 f"return {res.return_pct*100:+.1f}% · maxDD {res.max_drawdown*100:.1f}%</text>")
    parts.append(f"<text x='{width-pad}' y='{pad-8}' fill='{color}' font-size='13' "
                 f"text-anchor='end'>{_fmt_money(res.final_equity)}</text>")
    parts.append("</svg>")
    return "\n".join(parts)


def main():
    ap = argparse.ArgumentParser(description="Backtest the Fortress put-credit-spread strategy.")
    ap.add_argument("--csv", required=True, help="daily price CSV (Date + Close columns)")
    ap.add_argument("--symbol", default="UNKNOWN")
    ap.add_argument("--capital", type=float, default=1000.0, help="max loss per trade ($)")
    ap.add_argument("--equity", type=float, default=25000.0, help="starting account equity ($)")
    ap.add_argument("--svg", default=None, help="path to write the equity-curve SVG")
    args = ap.parse_args()

    bars = load_csv(args.csv)
    cfg = BacktestConfig(capital_per_trade=args.capital, starting_equity=args.equity)
    res = run_backtest(args.symbol, bars, cfg)
    print(render_report(res))

    svg_path = args.svg or os.path.splitext(args.csv)[0] + f"_{args.symbol}_equity.svg"
    with open(svg_path, "w") as f:
        f.write(render_svg(res))
    print(f"\nEquity curve → {svg_path}")
    return res


if __name__ == "__main__":
    main()
