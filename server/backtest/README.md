# Backtester — Fortress put-credit-spread strategy

Replays a daily price series through the bot's actual rules (sell ~5%-OTM, $5-wide
put credit spreads, ~10 days to expiry, weekly, take profit at 50% of credit) and
reports win rate, P/L, return, and drawdown.

```bash
cd server
python -m backtest.run --csv backtest/data/AAPL.csv --symbol AAPL --capital 1000
```

## What's real vs. modeled

- **Real:** the price path, the entry/exit logic, position sizing, P/L accounting,
  and every reported metric (all computed, nothing invented).
- **Modeled:** the option premiums. There's no free source of historical option
  quotes, so premiums come from **Black-Scholes** with implied vol estimated from
  the underlying's **realized volatility**. P/L is gross of commissions and assumes
  mid-price fills.

This matters a lot (see below), so it's stated up front rather than buried.

## First result — AAPL, 2015-02 → 2017-02 (real daily data, 506 sessions)

| IV / RV | Trades | Win % | Avg win | Avg loss | Total P/L | Return | Max DD |
|--------:|-------:|------:|--------:|---------:|----------:|-------:|-------:|
| 1.00 (pure realized vol) | 77 | 94% | +$47 | −$772 | −$470 | −1.9% | −7.8% |
| 1.15 | 77 | 95% | +$58 | −$889 | +$650 | +2.6% | −6.7% |
| 1.30 | 78 | 92% | +$70 | −$525 | +$1,919 | +7.7% | −4.9% |
| 1.50 | 77 | 94% | +$88 | −$511 | +$3,781 | +15.1% | −3.3% |

## Honest takeaways

1. **High win rate ≠ profitable.** ~93% of trades won, but the strategy was roughly
   break-even-to-slightly-negative under conservative pricing, because the few
   losers were **10–15× larger** than the average winner. That asymmetric payoff is
   the real risk premium-selling carries, and the backtest surfaces it instead of
   hiding it behind the win rate.
2. **Profitability hinges on the volatility risk premium.** Option sellers only make
   money on average when implied vol exceeds realized vol. At IV = realized (most
   conservative) it lost; at the empirically-typical IV/RV of ~1.1–1.3 for large
   caps it made roughly +3% to +8% over two years. The edge *is* that premium — not
   the price prediction.
3. **The sample is small.** One ticker, two years, 77 trades, with integer-contract
   sizing adding lumpiness — enough to show the engine works and the payoff shape,
   not enough to conclude the strategy is good or bad. Don't trade real money off
   this. Next steps: more tickers, more years, and real historical IV.

## Extending it

The loader reads any daily CSV with a `Date` and a close column, so drop more files
in `backtest/data/` and run them. When wired to live data (Alpaca, locally or
deployed), the same engine can pull real history instead of a CSV.
