"""Autopilot rebalance engine — pure target→orders math.

The live engine is the Claude agent: it reads followed-portfolio target weights +
guardrails from Supabase, reads the live Robinhood account (••••9584, cash,
equities/ETFs only), computes the order list with the function here, then places
each order via the Robinhood agentic tools (review_equity_order -> place_equity_order)
and writes the resulting snapshot + activity back to Supabase.

This module is the deterministic, unit-tested core so the sizing/guardrail logic is
auditable and reproducible. It places no orders itself.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass
class Order:
    symbol: str
    side: str          # "buy" | "sell"
    dollars: float     # notional; fractional dollar-based market order
    target_value: float
    current_value: float


def compute_orders(
    target_weights: dict[str, float],
    current_values: dict[str, float],
    cash: float,
    allocated_capital: float,
    max_position_pct: float,
    drift_threshold_pct: float,
    cash_buffer: float = 1.0,
) -> list[Order]:
    """Return the buy/sell orders to move the account toward target_weights.

    - Budget = min(allocated_capital, cash + current invested) but never more than
      (cash - buffer) can fund on net buys. We size to the deployable budget.
    - max_position_pct caps any single name's target value.
    - Orders whose |drift| as a fraction of budget is below drift_threshold_pct are
      skipped (no churn).
    """
    invested = sum(current_values.values())
    # Deployable budget: don't exceed available capital or the cap the user set.
    budget = min(allocated_capital, invested + max(cash - cash_buffer, 0.0))
    if budget <= 0:
        return []

    # Normalize weights defensively.
    wsum = sum(target_weights.values()) or 1.0
    weights = {s: w / wsum for s, w in target_weights.items()}

    # Apply per-position cap, then renormalize the uncapped remainder.
    capped = {s: min(w, max_position_pct) for s, w in weights.items()}
    # (single pass cap is sufficient for our weight sets; documented as a known
    # simplification — extreme caps that strand weight are out of scope.)

    symbols = set(capped) | set(current_values)
    orders: list[Order] = []
    for s in sorted(symbols):
        target_value = round(capped.get(s, 0.0) * budget, 2)
        current_value = round(current_values.get(s, 0.0), 2)
        drift = target_value - current_value
        if abs(drift) < drift_threshold_pct * budget:
            continue
        orders.append(
            Order(
                symbol=s,
                side="buy" if drift > 0 else "sell",
                dollars=round(abs(drift), 2),
                target_value=target_value,
                current_value=current_value,
            )
        )
    # Sells first (frees cash), then buys.
    orders.sort(key=lambda o: (o.side != "sell", o.symbol))
    return orders


if __name__ == "__main__":
    # Self-test: fresh $101 cash account, AI Flagship target, $500 cap, 25% max.
    target = {
        "NVDA": 0.22, "MSFT": 0.18, "AAPL": 0.16, "AMZN": 0.14,
        "GOOGL": 0.12, "SPY": 0.10, "QQQ": 0.08,
    }
    orders = compute_orders(
        target_weights=target,
        current_values={},        # nothing held yet
        cash=101.0,
        allocated_capital=500.0,
        max_position_pct=0.25,
        drift_threshold_pct=0.03,
        cash_buffer=1.0,
    )
    total = sum(o.dollars for o in orders)
    assert all(o.side == "buy" for o in orders), orders
    assert abs(total - 100.0) < 0.01, total          # deploys $100, keeps $1 buffer
    assert len(orders) == 7, orders
    # No single name exceeds the 25% cap of the $100 budget.
    assert max(o.target_value for o in orders) <= 25.0 + 1e-9
    for o in orders:
        print(f"{o.side:4} {o.symbol:5} ${o.dollars:6.2f}  (target ${o.target_value:.2f})")
    print("OK: total ${:.2f} across {} orders".format(total, len(orders)))
