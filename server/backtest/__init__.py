"""
Backtester for the Fortress put-credit-spread strategy.

Replays a daily price series through the bot's actual trading rules and reports
win rate, P/L, return, and drawdown. Option premiums are modeled with Black-Scholes
(IV ≈ realized vol), so results are a conservative estimate, not a tick-for-tick
replay of historical option fills. No invented numbers — everything is computed.
"""

from .data import Bar, load_csv
from .engine import BacktestConfig, BacktestResult, Trade, run_backtest

__all__ = ["Bar", "load_csv", "BacktestConfig", "BacktestResult", "Trade", "run_backtest"]
