# Autopilot engine — go-live runbook

The engine is the **Claude agent**, not a hosted server (only the agent can reach
Robinhood). It runs a rebalance cycle on demand / on a schedule while a session is
alive. The app and engine share state through **Supabase** (project `autopilot`,
ref `jeonlqduborydqiyhaey`).

## One rebalance cycle
1. Read `settings` (singleton) from Supabase. **If `killSwitch` is true → stop, do nothing.**
2. Read `follows`; for each followed portfolio read its `portfolios.holdings` → blend
   into one target-weight map (weighted by `allocationPct`).
3. Read live account **541689584** (••••9584) via Robinhood:
   `get_portfolio` (cash + buying power), `get_equity_positions`, `get_equity_quotes`.
4. `compute_orders(...)` in `rebalance.py` → buy/sell dollar orders, respecting:
   - budget = `min(allocatedCapital, invested + (cash − $1 buffer))`
   - `maxPositionPct` cap per name
   - `driftThresholdPct` skip (no churn)
5. **Only during regular market hours (9:30–16:00 ET).** Fractional/dollar orders
   only fill in `regular_hours`.
6. For each order: `review_equity_order` (surfaces buying-power/PDT/halt alerts) →
   `place_equity_order` (`type=market`, `dollar_amount`, `market_hours=regular_hours`).
   "Fully automatic" = no per-trade approval, **not** skipping the review safety check.
7. Re-read the account; write `account_snapshot` (upsert on `accountMasked`) and append
   `activity` rows (TRADE/REBALANCE) so the app reflects reality.

## Guardrails (hard)
- Account **541689584 only**; equities/ETFs only; cash account (no leverage).
- Never touch the margin (••••5204) or IRA (••••8436) accounts.
- Respect `killSwitch`, `allocatedCapital`, `maxPositionPct`, `driftThresholdPct`.
- Deploy never exceeds available buying power minus the $1 cash buffer.

## Current state (dry run @ 2026-06-15, premarket)
- Account holds **$101 cash, no positions**.
- Following: AI Flagship Fund (100%).
- Planned first deployment: **$100 across 7 names** (NVDA 22 / MSFT 18 / AAPL 16 /
  AMZN 14 / GOOGL 12 / SPY 10 / QQQ 8 %). Written to Supabase as a dry-run plan.
- **No orders placed.** Awaiting explicit go-live + market open.
"""
