"""
Price-series loading for the backtester.

Reads a CSV of daily prices and returns an ordered list of (date, close). The loader
is column-name tolerant so you can point it at most free OHLC datasets:

  • a "Date" column (any case), and
  • a close column: "Close", "Adj Close", "<TICKER>.Close", "<TICKER>.Adjusted", …

No pandas dependency — just the stdlib csv module.
"""

from __future__ import annotations

import csv
from dataclasses import dataclass


@dataclass
class Bar:
    date: str
    close: float


def _find_col(header: list[str], *candidates: str) -> int | None:
    low = [h.strip().lower() for h in header]
    for cand in candidates:
        c = cand.lower()
        for i, h in enumerate(low):
            if h == c:
                return i
    # fuzzy: any column that ends with the candidate (e.g. "AAPL.Close")
    for cand in candidates:
        c = cand.lower()
        for i, h in enumerate(low):
            if h.endswith(c):
                return i
    return None


def load_csv(path: str) -> list[Bar]:
    with open(path, newline="") as f:
        rows = list(csv.reader(f))
    if not rows:
        return []
    header, body = rows[0], rows[1:]
    di = _find_col(header, "date")
    # Prefer adjusted close (handles splits/dividends), then plain close.
    ci = _find_col(header, "adjusted", "adj close", "adj_close", ".adjusted",
                   "close", ".close")
    if di is None or ci is None:
        raise ValueError(f"couldn't find date/close columns in header: {header}")
    out: list[Bar] = []
    for r in body:
        if len(r) <= max(di, ci):
            continue
        try:
            out.append(Bar(date=r[di].strip(), close=float(r[ci])))
        except (ValueError, IndexError):
            continue
    out.sort(key=lambda b: b.date)
    return out
