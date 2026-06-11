"""
Option pricing for the backtester — plain Black-Scholes, no external deps.

We price European puts and build the short-put credit spread the bot trades. IV is
estimated from the underlying's own realized volatility (see engine). Because
implied vol is *usually higher* than realized vol, using realized vol here tends to
UNDER-state the premium a seller would really collect — i.e. results are biased
conservative, not rosy. That's deliberate and stated up front.
"""

from __future__ import annotations

import math


def _norm_cdf(x: float) -> float:
    """Standard-normal CDF via the error function (math.erf)."""
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def bs_put(spot: float, strike: float, t_years: float, sigma: float,
           r: float = 0.01) -> float:
    """Black-Scholes price of a European put (per share).

    At/near expiry (t≈0) or zero vol, returns intrinsic value max(K-S, 0).
    """
    if t_years <= 1e-9 or sigma <= 1e-9 or spot <= 0:
        return max(strike - spot, 0.0)
    vol_t = sigma * math.sqrt(t_years)
    d1 = (math.log(spot / strike) + (r + 0.5 * sigma * sigma) * t_years) / vol_t
    d2 = d1 - vol_t
    return strike * math.exp(-r * t_years) * _norm_cdf(-d2) - spot * _norm_cdf(-d1)


def put_credit_spread_value(spot: float, short_k: float, long_k: float,
                            t_years: float, sigma: float, r: float = 0.01) -> float:
    """Per-share value of a short put-credit spread (short higher strike, long
    lower strike). This is what it costs to buy the spread back to close — the
    seller's liability. Net credit at entry = this value at entry."""
    return bs_put(spot, short_k, t_years, sigma, r) - bs_put(spot, long_k, t_years, sigma, r)
