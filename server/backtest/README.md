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

## Bigger test — full S&P-500 universe, 2013–2018 (real daily data)

Ran across **all 501 names** in the S&P-500 5-year daily dataset (no cherry-picking),
selling put credit spreads on every name weekly. IV modeled at 1.15× realized vol.

| Metric | Value |
|---|---|
| Trades | 97,127 |
| Win rate | 94.9% |
| Avg P/L / trade | **+$17.30** |
| Total P/L | $1.68M |
| Peak capital actually at risk | $455k |
| **Return on peak capital** | **+369% over 5y ≈ +36%/yr** |
| Max drawdown | −10.9% |

**Read the headline carefully.** A naive "return %" (P/L ÷ a small starting balance)
prints absurd numbers like +1,680% — that's a *sizing artifact*, because the strategy
deploys far more buying power across hundreds of concurrent positions than the nominal
account. The honest figure is **return on peak capital-at-risk (~+36%/yr)**.

### Why even this honest number is not a green light

1. **Regime bias is enormous.** 2013–2018 was a historically calm bull market — the
   single best environment for selling puts. This window contains **no major crash**
   (the Feb-2018 "Volmageddon" is only at the very end). Short-vol strategies look
   spectacular right up until they don't.
2. **The tail that kills this strategy isn't in the data.** Losses average ~10–20×
   the winners; a real crash (2008, Mar-2020, 2022) spikes both the *rate* and *size*
   of losses. Backtesting it on a crash-free window flatters it badly.
3. **Survivorship:** the dataset is S&P-500 *constituents*, so chronically failing
   companies are under-represented.
4. **Premiums are modeled**, not historical option quotes (Black-Scholes, IV≈RV×k).
   Gross of commissions/slippage, mid-price fills assumed.

### Honest bottom line

The strategy has a **small, real positive edge per trade** (~+$17) and a high win
rate across a broad universe *in a benign regime*. That's consistent with how
vol-selling actually behaves — steady gains, then rare violent drawdowns. **Do not
trade real money off this.** A trustworthy verdict needs crash-period data (2008,
2020, 2022), real historical implied vol, and proper buying-power accounting.

## Extending it

- Single name: `python -m backtest.run --csv backtest/data/AAPL.csv --symbol AAPL`
- Multi-name / full universe: fetch a long-format daily CSV and use
  `backtest.data.load_long_csv(path, symbol)` + `backtest.portfolio.run_portfolio`.
  The 5-year dataset used above:
  `curl -o all_stocks_5yr.csv https://raw.githubusercontent.com/plotly/datasets/master/all_stocks_5yr.csv`
  (kept out of git — it's ~30 MB).
- When wired to live data (Alpaca, locally or deployed), the engine can pull real
  history instead of a CSV.
