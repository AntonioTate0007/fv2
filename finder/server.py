"""
Pump-Finder — surfaces small-cap stocks showing pre-runup behavior, the kind
that gets posted in Facebook stock groups *after* the gain is already locked
in. The goal is to catch them while there's still room to run.

Signal stack (all evidence-based, none guarantee anything):
    • price between $0.50 and $15        — the FB-pump price zone
    • float < 75M shares                  — small float = easy to move
    • relative volume ≥ 2x                — money is flowing in today
    • intraday change between +3% and +40% — early in the runup, not blown off
    • market cap < $1B                    — micro/small cap territory

Each candidate gets a 0-100 score that weights:
    relVol (40%) + change% room-to-run (25%) + float tightness (20%) +
    price compression (15%).

Routes:
    GET  /              → serves the single-page UI
    GET  /api/scan      → ranked JSON list of candidates
    GET  /healthz       → liveness

No auth. Bind to localhost by default; if you expose this, slap a reverse
proxy with basic auth in front.
"""

from __future__ import annotations

import asyncio
import logging
import math
import os
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from fastapi import FastAPI
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel

try:
    import yfinance as yf
    HAS_YF = True
except Exception:
    HAS_YF = False

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s — %(message)s")
log = logging.getLogger("finder")
# yfinance's own logger barks a full HTTP trace on every screener failure;
# we already log a one-liner ourselves and fall back to mock, so quiet it down.
logging.getLogger("yfinance").setLevel(logging.CRITICAL)

HERE = Path(__file__).parent
INDEX_HTML = HERE / "index.html"

# ── Filter knobs ───────────────────────────────────────────────────────────────

PRICE_MIN = 0.50
PRICE_MAX = 15.00
CHANGE_MIN_PCT = 3.0       # already moving
CHANGE_MAX_PCT = 40.0      # but not blown off the top
REL_VOL_MIN = 2.0          # at least 2× average volume
FLOAT_MAX = 75_000_000     # 75M shares
MCAP_MAX = 1_500_000_000   # $1.5B
MAX_RESULTS = 40

# Universe sources (yfinance "predefined" screeners). yfinance pulls these
# straight from Yahoo's public screener catalogue.
UNIVERSE_SCREENERS = [
    "day_gainers",
    "most_actives",
    "small_cap_gainers",
    "aggressive_small_caps",
]

# Cache so we don't hammer Yahoo on every refresh — the UI auto-refreshes
# every 30s; we only do real work every CACHE_TTL seconds.
CACHE_TTL = 25.0


# ── Models ─────────────────────────────────────────────────────────────────────

class Candidate(BaseModel):
    ticker: str
    name: str
    price: float
    changePct: float
    volume: int
    avgVolume: int
    relVol: float
    floatShares: Optional[int]
    marketCap: Optional[int]
    score: int
    reasons: list[str]
    yahooUrl: str
    fbSearchUrl: str
    stocktwitsUrl: str


class ScanResponse(BaseModel):
    asOf: float
    source: str          # "live" | "mock"
    count: int
    candidates: list[Candidate]


# ── Universe gathering ─────────────────────────────────────────────────────────

@dataclass
class _Row:
    symbol: str
    name: str
    price: float
    change_pct: float
    volume: int
    avg_volume: int
    float_shares: Optional[int]
    market_cap: Optional[int]


def _yf_screen(name: str, count: int = 100) -> list[dict]:
    """yfinance 0.2.x exposes Screener / set_predefined_body; old versions
    had a top-level yf.screen(). Try both and fall back to empty on any error.
    """
    # Modern path: Screener class.
    try:
        s = yf.Screener()
        s.set_predefined_body(name)
        # Some builds expose a size knob; others don't. Patch it best-effort.
        try:
            s.patch_body({"size": count})
        except Exception:
            pass
        res = s.response
    except Exception as e:
        log.warning("[yf] Screener %s failed: %s", name, e)
        # Fallback for very old yfinance.
        try:
            res = yf.screen(name, count=count)  # type: ignore[attr-defined]
        except Exception:
            return []
    if isinstance(res, dict):
        return res.get("quotes") or []
    if isinstance(res, list):
        return res
    return []


def _coerce_row(q: dict) -> Optional[_Row]:
    sym = q.get("symbol")
    if not sym:
        return None
    try:
        price = float(q.get("regularMarketPrice") or 0)
        change_pct = float(q.get("regularMarketChangePercent") or 0)
        volume = int(q.get("regularMarketVolume") or 0)
        avg_volume = int(q.get("averageDailyVolume3Month") or q.get("averageDailyVolume10Day") or 0)
    except (TypeError, ValueError):
        return None
    name = q.get("shortName") or q.get("longName") or sym
    float_shares = q.get("floatShares")
    market_cap = q.get("marketCap")
    try:
        float_shares = int(float_shares) if float_shares else None
    except (TypeError, ValueError):
        float_shares = None
    try:
        market_cap = int(market_cap) if market_cap else None
    except (TypeError, ValueError):
        market_cap = None
    return _Row(sym, str(name), price, change_pct, volume, avg_volume, float_shares, market_cap)


def _gather_universe() -> list[_Row]:
    seen: dict[str, _Row] = {}
    for screener in UNIVERSE_SCREENERS:
        for q in _yf_screen(screener, count=100):
            row = _coerce_row(q)
            if row and row.symbol not in seen:
                seen[row.symbol] = row
    return list(seen.values())


# ── Scoring ────────────────────────────────────────────────────────────────────

def _score(row: _Row) -> tuple[int, list[str]]:
    reasons: list[str] = []
    rel_vol = (row.volume / row.avg_volume) if row.avg_volume > 0 else 0.0

    # relVol component (0-40): saturates at 10x.
    rel_vol_pts = min(40.0, rel_vol * 4.0)
    if rel_vol >= 3:
        reasons.append(f"{rel_vol:.1f}× relative volume")

    # Runup-room component (0-25): peaks around +10%, falls off past +25%
    # because once it's already up huge, the easy money's gone.
    c = max(0.0, row.change_pct)
    runup_pts = 25.0 * math.exp(-((c - 10.0) ** 2) / 200.0)
    if 5 <= c <= 25:
        reasons.append(f"+{c:.1f}% intraday — early in the move")
    elif c > 25:
        reasons.append(f"+{c:.1f}% — late, watch for blow-off")

    # Float tightness (0-20): smaller float = more punchable. Saturates at 10M.
    if row.float_shares:
        float_m = row.float_shares / 1_000_000
        float_pts = max(0.0, min(20.0, 20.0 * (1.0 - math.log10(max(1.0, float_m)) / 2.0)))
        if float_m < 25:
            reasons.append(f"{float_m:.1f}M float — tight")
    else:
        float_pts = 8.0  # unknown float = neutral-ish, not a hard penalty

    # Price compression (0-15): cheaper stocks pump harder. Peaks under $5.
    if row.price <= 5:
        price_pts = 15.0
    elif row.price <= 10:
        price_pts = 10.0
    else:
        price_pts = 5.0
    if row.price <= 5:
        reasons.append(f"${row.price:.2f} — sub-$5 pump zone")

    total = int(round(rel_vol_pts + runup_pts + float_pts + price_pts))
    return min(100, total), reasons


def _passes_filters(row: _Row) -> bool:
    if not (PRICE_MIN <= row.price <= PRICE_MAX):
        return False
    if not (CHANGE_MIN_PCT <= row.change_pct <= CHANGE_MAX_PCT):
        return False
    if row.avg_volume <= 0 or (row.volume / row.avg_volume) < REL_VOL_MIN:
        return False
    if row.float_shares and row.float_shares > FLOAT_MAX:
        return False
    if row.market_cap and row.market_cap > MCAP_MAX:
        return False
    return True


def _to_candidate(row: _Row) -> Candidate:
    score, reasons = _score(row)
    rel_vol = (row.volume / row.avg_volume) if row.avg_volume > 0 else 0.0
    sym = row.symbol
    return Candidate(
        ticker=sym,
        name=row.name,
        price=round(row.price, 2),
        changePct=round(row.change_pct, 2),
        volume=row.volume,
        avgVolume=row.avg_volume,
        relVol=round(rel_vol, 2),
        floatShares=row.float_shares,
        marketCap=row.market_cap,
        score=score,
        reasons=reasons,
        yahooUrl=f"https://finance.yahoo.com/quote/{sym}",
        fbSearchUrl=f"https://www.facebook.com/search/posts/?q=%24{sym}",
        stocktwitsUrl=f"https://stocktwits.com/symbol/{sym}",
    )


# ── Mock fallback (when yfinance is offline / sandboxed) ──────────────────────

def _mock_rows() -> list[_Row]:
    """Snapshot inspired by the screenshots: SLS + AIIO style biotech pumps."""
    return [
        _Row("SLS", "SELLAS Life Sciences Group", 7.72, 18.15, 14_200_000, 3_100_000, 22_400_000, 410_000_000),
        _Row("AIIO", "AInos Inc",                  4.18, 32.50,  8_900_000, 1_400_000, 11_800_000, 92_000_000),
        _Row("CRKN", "Crown Electrokinetics",      0.62,  8.40, 32_500_000, 9_800_000, 18_400_000, 19_000_000),
        _Row("INVO", "INVO Bioscience",            1.47, 12.20,  4_300_000,   780_000,  7_200_000, 24_000_000),
        _Row("NUKK", "Nukkleus Inc",               2.84, 22.10,  6_100_000, 1_200_000,  9_400_000, 38_000_000),
        _Row("BNRG", "Brenmiller Energy",          1.92,  5.10,  1_800_000,   410_000, 14_200_000, 31_000_000),
        _Row("PEGY", "Pineapple Energy",           0.84, 14.80,  9_400_000, 2_100_000, 22_900_000, 17_000_000),
    ]


# ── Cache + scan ───────────────────────────────────────────────────────────────

_cache: dict[str, object] = {"ts": 0.0, "payload": None}
_lock = asyncio.Lock()


async def _do_scan() -> ScanResponse:
    if not HAS_YF:
        log.info("[scan] yfinance not installed — using mock")
        rows = _mock_rows()
        source = "mock"
    else:
        loop = asyncio.get_running_loop()
        try:
            rows = await asyncio.wait_for(loop.run_in_executor(None, _gather_universe), timeout=20.0)
            source = "live" if rows else "mock"
            if not rows:
                rows = _mock_rows()
        except (asyncio.TimeoutError, Exception) as e:
            log.warning("[scan] live fetch failed (%s) — falling back to mock", e)
            rows = _mock_rows()
            source = "mock"

    passing = [r for r in rows if _passes_filters(r)]
    cands = sorted((_to_candidate(r) for r in passing), key=lambda c: c.score, reverse=True)
    cands = cands[:MAX_RESULTS]
    return ScanResponse(asOf=time.time(), source=source, count=len(cands), candidates=cands)


async def _cached_scan() -> ScanResponse:
    async with _lock:
        now = time.time()
        if _cache["payload"] and (now - float(_cache["ts"])) < CACHE_TTL:
            return _cache["payload"]  # type: ignore[return-value]
        payload = await _do_scan()
        _cache["ts"] = now
        _cache["payload"] = payload
        return payload


# ── App ────────────────────────────────────────────────────────────────────────

app = FastAPI(title="Pump-Finder", version="0.1.0")


@app.get("/healthz")
def healthz():
    return {"ok": True, "yfinance": HAS_YF}


@app.get("/api/scan", response_model=ScanResponse)
async def scan():
    return await _cached_scan()


@app.get("/")
def index():
    if INDEX_HTML.exists():
        return FileResponse(INDEX_HTML)
    return JSONResponse({"error": "index.html missing"}, status_code=500)


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", "8000"))
    uvicorn.run(app, host="127.0.0.1", port=port)
