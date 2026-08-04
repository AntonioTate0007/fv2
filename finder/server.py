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
    GET  /                              → serves the single-page UI
    GET  /api/scan                      → ranked JSON list of candidates
    GET  /healthz                       → liveness
    POST /api/notifications/register    → device registers FCM token
    GET  /api/notifications/config      → current alert threshold + state
    POST /api/notifications/config      → update threshold (admin token only)
    POST /api/notifications/test        → fire a test push (admin token only)

Env vars:
    PORT                       — Render injects this
    FIREBASE_CREDENTIALS_JSON  — service-account JSON, enables FCM push
    PUMP_ALERT_MIN_SCORE       — score threshold for auto-alerts (default 85)
    PUMP_ALERT_INTERVAL_SEC    — scan interval (default 90)
    PUMP_ADMIN_TOKEN           — bearer for /api/notifications/config + /test
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import os
import secrets
import time
from dataclasses import dataclass
from datetime import datetime, time as dtime, timezone, timedelta
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

try:
    import yfinance as yf
    HAS_YF = True
except Exception:
    HAS_YF = False

try:
    import firebase_admin
    from firebase_admin import credentials, messaging
    HAS_FIREBASE = True
except Exception:
    HAS_FIREBASE = False

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s — %(message)s")
log = logging.getLogger("finder")
# yfinance's own logger barks a full HTTP trace on every screener failure;
# we already log a one-liner ourselves and fall back to mock, so quiet it down.
logging.getLogger("yfinance").setLevel(logging.CRITICAL)

HERE = Path(__file__).parent
INDEX_HTML = HERE / "index.html"
STATIC_DIR = HERE / "static"

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


# ── FCM (Firebase Cloud Messaging) ─────────────────────────────────────────────
#
# When FIREBASE_CREDENTIALS_JSON is set, the backend can push alerts to any
# registered device the moment a high-score candidate appears. Free-tier Render
# instances sleep after 15 min idle, so this is a "best effort" channel — the
# Android client also polls via WorkManager so it doesn't depend on the server
# being awake.

FCM_TOKENS: set[str] = set()
ALERT_MIN_SCORE = int(os.getenv("PUMP_ALERT_MIN_SCORE", "85"))
ALERT_INTERVAL_SEC = int(os.getenv("PUMP_ALERT_INTERVAL_SEC", "90"))
ALERT_DEDUPE_TTL = timedelta(hours=4)
_alerted: dict[str, datetime] = {}  # ticker → last-alerted UTC time


def init_firebase() -> bool:
    if not HAS_FIREBASE:
        return False
    if firebase_admin._apps:
        return True
    raw = os.getenv("FIREBASE_CREDENTIALS_JSON")
    if not raw:
        return False
    try:
        cred = credentials.Certificate(json.loads(raw))
        firebase_admin.initialize_app(cred)
        return True
    except Exception as e:
        log.exception("[firebase] init failed: %s", e)
        return False


def _bearer(authorization: Optional[str]) -> Optional[str]:
    if not authorization:
        return None
    if authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    return None


def require_admin(authorization: Optional[str] = Header(default=None)) -> None:
    """Gate config/test routes when PUMP_ADMIN_TOKEN is set. Open by default
    so the scanner is hackable in dev — set the env var on Render to lock it."""
    expected = os.getenv("PUMP_ADMIN_TOKEN")
    if not expected:
        return
    given = _bearer(authorization)
    if not given or not secrets.compare_digest(given, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "bad admin token")


class FcmTokenRequest(BaseModel):
    token: str


class AlertConfig(BaseModel):
    minScore: int
    intervalSec: int
    firebaseEnabled: bool
    devicesRegistered: int


class AlertConfigUpdate(BaseModel):
    minScore: Optional[int] = None


def _us_market_hours() -> bool:
    """Roughly 09:30 – 16:00 US Eastern, Mon-Fri. Not holiday-aware — the
    cost of an extra scan on a closed market is a wasted Yahoo call, no harm.
    """
    # US Eastern = UTC-4 during DST, UTC-5 otherwise. Approximate via UTC-5
    # offset since the alert window is generous on both sides.
    now_utc = datetime.now(tz=timezone.utc)
    ny = now_utc - timedelta(hours=5)  # standard time; close enough
    if ny.weekday() >= 5:
        return False
    hhmm = ny.time()
    return dtime(9, 0) <= hhmm <= dtime(17, 0)


def _send_pump_alert(c: Candidate) -> int:
    """Send an FCM data-message to every registered token. Returns success count."""
    if not init_firebase() or not FCM_TOKENS:
        return 0
    try:
        msg = messaging.MulticastMessage(
            data={
                "type": "pump_alert",
                "ticker": c.ticker,
                "name": c.name,
                "score": str(c.score),
                "price": f"{c.price:.2f}",
                "changePct": f"{c.changePct:.2f}",
                "relVol": f"{c.relVol:.2f}",
                "yahooUrl": c.yahooUrl,
            },
            tokens=list(FCM_TOKENS),
        )
        resp = messaging.send_each_for_multicast(msg)
        # Prune dead tokens so we don't keep retrying them.
        for token, result in zip(list(FCM_TOKENS), resp.responses):
            if not result.success:
                err = getattr(result.exception, "code", "")
                if err in ("registration-token-not-registered", "invalid-argument"):
                    FCM_TOKENS.discard(token)
        log.info("[fcm] pump_alert %s score=%d → %d sent, %d failed",
                 c.ticker, c.score, resp.success_count, resp.failure_count)
        return resp.success_count
    except Exception as e:
        log.exception("[fcm] send failed: %s", e)
        return 0


async def _alert_loop():
    """Periodically scan and FCM-push any new high-score candidate."""
    while True:
        try:
            if _us_market_hours():
                payload = await _cached_scan()
                now = datetime.now(tz=timezone.utc)
                for c in payload.candidates:
                    if c.score < ALERT_MIN_SCORE:
                        continue
                    last = _alerted.get(c.ticker)
                    if last and (now - last) < ALERT_DEDUPE_TTL:
                        continue
                    if _send_pump_alert(c) > 0:
                        _alerted[c.ticker] = now
                # Drop old dedupe entries so memory doesn't grow unbounded.
                cutoff = now - ALERT_DEDUPE_TTL * 2
                for k, ts in list(_alerted.items()):
                    if ts < cutoff:
                        del _alerted[k]
        except Exception as e:
            log.exception("[alert] loop iteration failed: %s", e)
        await asyncio.sleep(ALERT_INTERVAL_SEC)


# ── App ────────────────────────────────────────────────────────────────────────

from contextlib import asynccontextmanager  # noqa: E402  (local import after consts)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    fb = init_firebase()
    log.info("[startup] firebase=%s yfinance=%s alert_min=%d interval=%ds",
             "on" if fb else "off", HAS_YF, ALERT_MIN_SCORE, ALERT_INTERVAL_SEC)
    alert_task = asyncio.create_task(_alert_loop())
    try:
        yield
    finally:
        alert_task.cancel()
        try:
            await alert_task
        except asyncio.CancelledError:
            pass


app = FastAPI(title="Pump-Finder", version="0.2.0", lifespan=lifespan)

if STATIC_DIR.exists():
    # Serve manifest, icons, and the service worker. Service worker MUST be
    # served from /static/sw.js (its scope is set by URL location); the SW
    # itself sets `scope: '/'` via the registration call in index.html.
    app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")


@app.get("/healthz")
def healthz():
    return {
        "ok": True,
        "yfinance": HAS_YF,
        "firebase": init_firebase(),
        "devices": len(FCM_TOKENS),
        "alertMinScore": ALERT_MIN_SCORE,
    }


@app.get("/api/scan", response_model=ScanResponse)
async def scan():
    return await _cached_scan()


@app.post("/api/notifications/register")
def register_token(req: FcmTokenRequest):
    """Open registration so the mobile app can self-enrol without admin auth.
    Tokens are short, opaque, and harmless without the matching Firebase project."""
    if req.token:
        FCM_TOKENS.add(req.token)
    return {"ok": True, "devices": len(FCM_TOKENS)}


@app.get("/api/notifications/config", response_model=AlertConfig)
def get_alert_config():
    return AlertConfig(
        minScore=ALERT_MIN_SCORE,
        intervalSec=ALERT_INTERVAL_SEC,
        firebaseEnabled=init_firebase(),
        devicesRegistered=len(FCM_TOKENS),
    )


@app.post("/api/notifications/config",
          response_model=AlertConfig,
          dependencies=[Depends(require_admin)])
def update_alert_config(req: AlertConfigUpdate):
    global ALERT_MIN_SCORE
    if req.minScore is not None:
        ALERT_MIN_SCORE = max(0, min(100, int(req.minScore)))
    return get_alert_config()


@app.post("/api/notifications/test", dependencies=[Depends(require_admin)])
def fire_test_alert():
    """Fires a synthetic 'AAPL @ score 92' alert to every registered device.
    Use this after wiring the Android app to confirm the FCM pipe end-to-end."""
    if not init_firebase():
        raise HTTPException(503, "Firebase not configured")
    if not FCM_TOKENS:
        raise HTTPException(400, "No devices registered")
    test_c = Candidate(
        ticker="TEST", name="Pump-Finder test",
        price=4.20, changePct=12.5, volume=8_000_000, avgVolume=1_500_000,
        relVol=5.3, floatShares=18_000_000, marketCap=85_000_000,
        score=92, reasons=["test alert"],
        yahooUrl="https://finance.yahoo.com/quote/AAPL",
        fbSearchUrl="https://www.facebook.com/search/posts/?q=%24AAPL",
        stocktwitsUrl="https://stocktwits.com/symbol/AAPL",
    )
    sent = _send_pump_alert(test_c)
    return {"sent": sent, "devices": len(FCM_TOKENS)}


@app.get("/")
def index():
    if INDEX_HTML.exists():
        return FileResponse(INDEX_HTML)
    return JSONResponse({"error": "index.html missing"}, status_code=500)


@app.get("/sw.js")
def service_worker():
    """Served from the root so its scope covers '/' (intercepts /api/scan).
    A SW served from /static/ would be scope-limited to /static/.
    """
    sw_path = STATIC_DIR / "sw.js"
    if not sw_path.exists():
        return JSONResponse({"error": "sw.js missing"}, status_code=404)
    return FileResponse(
        sw_path,
        media_type="application/javascript",
        headers={"Service-Worker-Allowed": "/", "Cache-Control": "no-cache"},
    )


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", "8000"))
    # Bind 0.0.0.0 when PORT is set (Render / container), else loopback for dev.
    host = "0.0.0.0" if os.getenv("PORT") else "127.0.0.1"
    uvicorn.run(app, host=host, port=port)
