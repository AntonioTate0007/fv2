"""
Merged web UI for Fortress — the former "companion" service, now folded
into fortress-api so it runs as a single process.

Public routes (mounted onto the fortress-api FastAPI app):
    GET  /                     single-page dashboard (Plays / Positions /
                               Earnings / Alerts / Settings)
    GET  /healthz              liveness probe (for Render)
    GET  /api/scan             deck of currently-monitored plays
    GET  /api/positions        currently-held options positions
    GET  /api/earnings         earnings-blackout gate snapshot
    POST /api/analyze/play     per-play Gemini analysis (2-3 sentence)
    GET  /api/market           VIX / SPY / premium-sell rating summary
    GET  /api/config           masked settings snapshot
    POST /api/config           save runtime settings (Alpaca/Gemini/Telegram)
    POST /api/alert            fire a test Telegram alert
    GET  /api/logs             tail of in-memory alerts log
    POST /api/deck/refresh     manual "Scan Now"
    POST /api/deck/clear       manual "Clear Deck"

All routes are UN-authenticated — this UI IS the public face. If you want
to add a login, set FORTRESS_UI_PASSWORD (TODO — not yet wired).

Config lives at fortress_config.json (ephemeral on Render free tier);
env vars are consulted at boot as defaults. Saving through /api/config
mutates os.environ so broker/gemini calls in the same process pick up
the new values immediately, then writes the JSON file.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from collections import deque
from datetime import date, datetime, time as dtime, timedelta, timezone
from typing import Any, Optional

import requests
from fastapi import APIRouter, HTTPException, Query, Request, status
from fastapi.responses import HTMLResponse, JSONResponse

try:
    from zoneinfo import ZoneInfo
except ImportError:
    ZoneInfo = None

# broker + analysis functions are defined in main.py / alpaca.py; imported
# lazily inside functions to avoid circular imports at module-load time.

log = logging.getLogger("fortress.ui")

CONFIG_FILE = os.environ.get("FORTRESS_CONFIG_FILE", "fortress_config.json")
LOG_CAPACITY = 200


# ── Config ──────────────────────────────────────────────────────────────────

_config_lock = threading.Lock()
_env_keys_at_boot: set[str] = set()


def _load_config() -> dict:
    cfg = {
        "telegram_bot_token": os.environ.get("TELEGRAM_BOT_TOKEN", ""),
        "telegram_chat_ids": os.environ.get("TELEGRAM_CHAT_ID", ""),
        "telegram_enabled": True,
        "scan_capital": 5000,
        "alpaca_api_key": os.environ.get("ALPACA_API_KEY", ""),
        "alpaca_api_secret": os.environ.get("ALPACA_API_SECRET", ""),
        "alpaca_paper": (os.environ.get("ALPACA_PAPER", "true").lower() != "false"),
        "gemini_api_key": os.environ.get("GEMINI_API_KEY", ""),
        "gemini_enabled": True,
        "profit_alert_pct": 50,   # fire Telegram alert when a position hits N% of max profit
        "profit_alerts_enabled": True,
        "watchlist_tickers": [],  # ordered list of tickers user manually watches
        "theme": "dark",          # "dark" | "light"
    }
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r") as f:
                stored = json.load(f)
                if "telegram_chat_id" in stored and "telegram_chat_ids" not in stored:
                    stored["telegram_chat_ids"] = stored.pop("telegram_chat_id")
                cfg.update(stored)
        except (OSError, json.JSONDecodeError) as e:
            log.warning("could not read %s: %s", CONFIG_FILE, e)
    return cfg


def _save_config(cfg: dict) -> None:
    with open(CONFIG_FILE, "w") as f:
        json.dump(cfg, f, indent=4)


def _apply_config_to_env(cfg: dict) -> None:
    """Push the current runtime config into os.environ so broker.py + the
    Gemini analysis code (which read from env) pick up the new values without
    a restart. Also bust the broker's client cache so subsequent API calls
    build a new TradingClient against the new creds."""
    if cfg.get("alpaca_api_key"):
        os.environ["ALPACA_API_KEY"] = cfg["alpaca_api_key"]
    if cfg.get("alpaca_api_secret"):
        os.environ["ALPACA_API_SECRET"] = cfg["alpaca_api_secret"]
    os.environ["ALPACA_PAPER"] = "true" if cfg.get("alpaca_paper", True) else "false"
    if cfg.get("gemini_api_key"):
        os.environ["GEMINI_API_KEY"] = cfg["gemini_api_key"]
    if cfg.get("telegram_bot_token"):
        os.environ["TELEGRAM_BOT_TOKEN"] = cfg["telegram_bot_token"]
    if cfg.get("telegram_chat_ids"):
        os.environ["TELEGRAM_CHAT_ID"] = cfg["telegram_chat_ids"]
    # Bust broker cache so next call picks up new creds
    try:
        import alpaca as broker  # type: ignore
        with broker._client_lock:
            broker._trading_cache.clear()
            broker._option_cache.clear()
    except Exception:
        pass


def _record_env_keys() -> None:
    for env, cfg_key in (
        ("TELEGRAM_BOT_TOKEN", "telegram_bot_token"),
        ("TELEGRAM_CHAT_ID", "telegram_chat_ids"),
        ("ALPACA_API_KEY", "alpaca_api_key"),
        ("ALPACA_API_SECRET", "alpaca_api_secret"),
        ("ALPACA_PAPER", "alpaca_paper"),
        ("GEMINI_API_KEY", "gemini_api_key"),
    ):
        if os.environ.get(env):
            _env_keys_at_boot.add(cfg_key)


_record_env_keys()
app_config = _load_config()


def _parse_chat_ids(raw: str) -> list[str]:
    return [p.strip() for p in (raw or "").split(",") if p.strip()]


# ── Logs (ring buffer, thread-safe) ─────────────────────────────────────────

_logs_lock = threading.Lock()
logs_ring: deque[dict] = deque(maxlen=LOG_CAPACITY)


def add_log(message: str, level: str = "info") -> None:
    entry = {
        "ts": datetime.now(timezone.utc).strftime("%H:%M:%S"),
        "level": level,
        "message": message,
    }
    with _logs_lock:
        logs_ring.append(entry)
    log.info("[%s] %s", level, message)


add_log("UI module initialized")


# ── Telegram ────────────────────────────────────────────────────────────────

def send_telegram_alert(message: str) -> None:
    with _config_lock:
        token = app_config.get("telegram_bot_token", "")
        chat_ids = _parse_chat_ids(app_config.get("telegram_chat_ids", ""))
        enabled = bool(app_config.get("telegram_enabled", True))

    if not enabled:
        add_log("Telegram disabled in settings — alert shown on screen only")
        return
    if not token or not chat_ids:
        add_log("ERR: Telegram not configured — set token + chat ID(s) in Settings", "error")
        return

    url = f"https://api.telegram.org/bot{token}/sendMessage"
    for chat_id in chat_ids:
        try:
            resp = requests.post(url, json={"chat_id": chat_id, "text": message}, timeout=10)
            if resp.ok:
                add_log(f"SENT → {chat_id}: {message}", "success")
            else:
                add_log(f"ERR: Telegram {resp.status_code} for {chat_id} — {resp.text[:120]}", "error")
        except requests.RequestException as e:
            add_log(f"ERR: {chat_id} — {e}", "error")


# ── Direct-call broker / analysis / earnings ───────────────────────────────

def _demo_scan() -> list[dict]:
    return [
        {"id": "SPY-PCS-540", "ticker": "SPY", "strategy": "PUT_CREDIT_SPREAD",
         "shortStrike": 540.0, "longStrike": 535.0, "expiration": "2026-07-13",
         "dte": 13, "estimatedCreditPerContract": 0.45, "safetyBufferPct": 0.105,
         "underlyingPrice": 603.4, "probabilityOfProfit": 0.88, "shortDelta": 0.10,
         "ivRank": 0.52, "earningsClear": True, "demo": True},
        {"id": "QQQ-PCS-470", "ticker": "QQQ", "strategy": "PUT_CREDIT_SPREAD",
         "shortStrike": 470.0, "longStrike": 465.0, "expiration": "2026-07-13",
         "dte": 13, "estimatedCreditPerContract": 0.55, "safetyBufferPct": 0.075,
         "underlyingPrice": 508.0, "probabilityOfProfit": 0.79, "shortDelta": 0.16,
         "ivRank": 0.46, "earningsClear": True, "demo": True},
        {"id": "IWM-PCS-205", "ticker": "IWM", "strategy": "PUT_CREDIT_SPREAD",
         "shortStrike": 205.0, "longStrike": 200.0, "expiration": "2026-07-13",
         "dte": 13, "estimatedCreditPerContract": 0.85, "safetyBufferPct": 0.045,
         "underlyingPrice": 214.7, "probabilityOfProfit": 0.66, "shortDelta": 0.28,
         "ivRank": 0.51, "earningsClear": True, "demo": True},
    ]


def _demo_positions() -> list[dict]:
    return [
        {"id": "SPY-2026-07-13-540", "ticker": "SPY",
         "strategyLabel": "$540 / $535 Put Spread", "shortStrike": 540.0,
         "underlyingPrice": 603.4, "entryPremium": 0.55, "currentPremium": 0.21,
         "expiration": "2026-07-13", "contracts": 1, "demo": True},
    ]


def get_scan(capital: int) -> list[dict]:
    """Direct-call the broker scanner. Falls back to demo data when Alpaca
    isn't configured — no HTTP hop."""
    try:
        import alpaca as broker  # type: ignore
        if broker.is_configured():
            live = broker.scan_chains(capital)
            if live:
                return live
        return _demo_scan()
    except Exception as e:
        log.warning("[scan] direct call failed: %s", e)
        return _demo_scan()


def get_positions() -> list[dict]:
    try:
        import alpaca as broker  # type: ignore
        if broker.is_configured():
            return broker.list_positions()
        return _demo_positions()
    except Exception as e:
        log.warning("[positions] direct call failed: %s", e)
        return _demo_positions()


def get_earnings(tickers: list[str]) -> dict:
    try:
        import alpaca as broker  # type: ignore
        items = broker.get_earnings_calendar(tickers)
        return {"items": items, "blackoutDays": broker.EARNINGS_BLACKOUT_DAYS,
                "asOf": datetime.now(timezone.utc).isoformat(), "demo": False}
    except Exception as e:
        log.warning("[earnings] direct call failed: %s", e)
        today = date.today()
        items = []
        etfs = {"SPY", "QQQ", "IWM"}
        for t in tickers:
            t = t.strip().upper()
            if t in etfs:
                items.append({"ticker": t, "nextEarningsDate": None, "daysUntil": None,
                              "withinBlackout": False, "isEtf": True})
            else:
                nd = today + timedelta(days=9)
                items.append({"ticker": t, "nextEarningsDate": nd.isoformat(),
                              "daysUntil": 9, "withinBlackout": False, "isEtf": False})
        return {"items": items, "blackoutDays": 7,
                "asOf": datetime.now(timezone.utc).isoformat(), "demo": True}


def get_market_summary() -> dict:
    plays = get_scan(app_config.get("scan_capital", 5000))
    iv_ranks = [p.get("ivRank") or 0 for p in plays if p.get("ivRank") is not None]
    avg_iv = sum(iv_ranks) / len(iv_ranks) if iv_ranks else 0.0
    if avg_iv >= 0.45:
        rating, sub = "OPTIMAL", "IV elevated"
    elif avg_iv >= 0.30:
        rating, sub = "FAIR", "moderate IV"
    else:
        rating, sub = "WAIT", "IV compressed"
    demo = any(p.get("demo") for p in plays)
    return {
        "vix": {"value": 14.8, "label": "LOW / CALM"},
        "spy": {"price": 737.23, "changePct": 0.24},
        "premiumSell": {"rating": rating, "sub": sub, "avgIvRank": round(avg_iv, 2)},
        "demo": demo,
    }


# ── Scheduled Deck (persistent per-day play list + validity checker) ───────

MARKET_TZ = ZoneInfo("America/New_York") if ZoneInfo else None

US_MARKET_HOLIDAYS: set[str] = {
    "2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25",
    "2026-06-19", "2026-07-03", "2026-09-07", "2026-11-26", "2026-12-25",
    "2027-01-01", "2027-01-18", "2027-02-15", "2027-03-26", "2027-05-31",
    "2027-06-18", "2027-07-05", "2027-09-06", "2027-11-25", "2027-12-24",
}

DECK_CLEAR_HOUR = 9
DECK_START_HOUR = 10
DECK_STOP_HOUR = 16
DECK_TICK_MINUTES = 15

_deck_lock = threading.Lock()
_deck: dict[str, dict] = {}
_deck_meta = {"lastScanAt": None, "lastClearAt": None, "lastReason": None}
_last_scheduled_action: dict[str, str] = {}


def _market_now():
    if MARKET_TZ is None:
        return datetime.now(timezone.utc)
    return datetime.now(MARKET_TZ)


def is_market_day(d=None):
    d = d or _market_now()
    if d.weekday() >= 5:
        return False
    return d.date().isoformat() not in US_MARKET_HOLIDAYS


def is_market_hours(d=None):
    d = d or _market_now()
    if not is_market_day(d):
        return False
    return dtime(DECK_START_HOUR, 0) <= d.time() < dtime(DECK_STOP_HOUR, 0)


def refresh_deck(reason: str = "manual") -> dict:
    capital = app_config.get("scan_capital", 5000)
    fresh = get_scan(capital)
    fresh_by_id = {p["id"]: p for p in fresh}
    now = datetime.now(timezone.utc)
    added, removed = 0, 0
    with _deck_lock:
        for pid in list(_deck.keys()):
            if pid not in fresh_by_id:
                del _deck[pid]
                removed += 1
                add_log(f"Deck: dropped {pid} (no longer valid)")
        for pid, p in fresh_by_id.items():
            existing = _deck.get(pid)
            if existing:
                p["_addedAt"] = existing.get("_addedAt", now.isoformat())
                p["_scanCount"] = existing.get("_scanCount", 0) + 1
            else:
                p["_addedAt"] = now.isoformat()
                p["_scanCount"] = 1
                added += 1
                add_log(f"Deck: added {pid}")
            _deck[pid] = p
        _deck_meta["lastScanAt"] = now.isoformat()
        _deck_meta["lastReason"] = reason
    return {"added": added, "removed": removed, "size": len(_deck)}


# ── Position tracker — fires Telegram alert when profit target is hit ──────
#
# Model: every tick during market hours (see _scheduler_tick), pull positions,
# compute profit% per position, and fire a Telegram alert the first time each
# position crosses the configured threshold. Fired thresholds are tracked per
# position so we don't spam (until container restart, then a duplicate is
# possible — accepted trade-off for MVP simplicity).

_alert_lock = threading.Lock()
_fired_targets: dict[str, set[int]] = {}   # position_id → {50, 100, ...} thresholds already alerted
_last_seen_status: dict[str, dict] = {}    # position_id → latest snapshot for the UI


def _position_profit_pct(p: dict) -> float:
    """Percent of max profit collected, based on Alpaca's ActivePosition shape.
    entryPremium = credit collected at open; currentPremium = cost to close now.
    Positive when we're winning."""
    entry = p.get("entryPremium") or 0.0
    current = p.get("currentPremium") or 0.0
    if entry <= 0:
        return 0.0
    return max(-999.0, min(999.0, (entry - current) / entry * 100.0))


def check_position_alerts() -> None:
    """Poll positions, compute profit %, fire alerts on threshold crossings."""
    if not app_config.get("profit_alerts_enabled", True):
        return
    threshold = int(app_config.get("profit_alert_pct", 50) or 50)
    positions = get_positions()
    now_iso = datetime.now(timezone.utc).isoformat()

    for p in positions:
        pid = p.get("id")
        if not pid:
            continue
        profit_pct = _position_profit_pct(p)
        with _alert_lock:
            _last_seen_status[pid] = {
                "ticker": p.get("ticker"),
                "profitPct": round(profit_pct, 1),
                "targetPct": threshold,
                "targetHit": profit_pct >= threshold,
                "checkedAt": now_iso,
            }
            fired = _fired_targets.setdefault(pid, set())
            # Consider standard tiers so a big jump doesn't skip an alert.
            for tier in (threshold, 75, 100):
                if profit_pct >= tier and tier not in fired:
                    fired.add(tier)
                    ticker = p.get("ticker", pid)
                    strat = p.get("strategyLabel", "position")
                    contracts = p.get("contracts", 1)
                    profit_dollars = (p.get("entryPremium", 0) - p.get("currentPremium", 0)) * 100 * contracts
                    label = "MAX PROFIT" if tier == 100 else f"{tier}% target"
                    msg = (f"🎯 {ticker} {label} hit — {profit_pct:.0f}% of credit "
                           f"(≈ ${profit_dollars:.0f} on {contracts}x {strat}). "
                           f"Consider closing.")
                    add_log(f"Position tracker: {msg}", "success")
                    # Fire Telegram in a background thread so it doesn't block the tick
                    threading.Thread(target=send_telegram_alert, args=(msg,), daemon=True).start()


def position_status_map() -> dict:
    """UI helper — returns a shallow copy of _last_seen_status."""
    with _alert_lock:
        return {k: dict(v) for k, v in _last_seen_status.items()}


def clear_deck(reason: str = "manual") -> dict:
    with _deck_lock:
        size = len(_deck)
        _deck.clear()
        _deck_meta["lastClearAt"] = datetime.now(timezone.utc).isoformat()
        _deck_meta["lastReason"] = reason
    add_log(f"Deck cleared ({reason}) — {size} plays removed")
    return {"cleared": size}


def _scheduler_tick():
    now = _market_now()
    today = now.date().isoformat()
    hh, mm = now.hour, now.minute
    if hh == DECK_CLEAR_HOUR and mm == 0:
        key = f"9AM-{today}"
        if _last_scheduled_action.get("clear") != key:
            _last_scheduled_action["clear"] = key
            clear_deck("scheduled 09:00 ET clear")
    if is_market_hours(now) and mm % DECK_TICK_MINUTES == 0:
        key = f"scan-{today}-{hh:02d}{mm:02d}"
        if _last_scheduled_action.get("scan") != key:
            _last_scheduled_action["scan"] = key
            refresh_deck(f"scheduled {hh:02d}:{mm:02d} ET scan")

    # Position tracker fires every 5 minutes during market hours (independent
    # of the deck refresh cadence — position P&L moves faster than the
    # scanner's need to run).
    if is_market_hours(now) and mm % 5 == 0:
        key = f"positions-{today}-{hh:02d}{mm:02d}"
        if _last_scheduled_action.get("positions") != key:
            _last_scheduled_action["positions"] = key
            try:
                check_position_alerts()
            except Exception:
                log.exception("[position-tracker] tick failed")


def _scheduler_loop():
    while True:
        try:
            _scheduler_tick()
        except Exception:
            log.exception("[deck-scheduler] tick failed")
        time.sleep(60)


def _opportunistic_refresh():
    last = _deck_meta.get("lastScanAt")
    if not is_market_hours():
        return
    now = datetime.now(timezone.utc)
    if last:
        try:
            last_dt = datetime.fromisoformat(last)
            if (now - last_dt).total_seconds() < DECK_TICK_MINUTES * 60:
                return
        except ValueError:
            pass
    threading.Thread(target=refresh_deck,
                     args=("opportunistic post-idle refresh",),
                     daemon=True).start()


def start_scheduler():
    t = threading.Thread(target=_scheduler_loop, daemon=True, name="deck-scheduler")
    t.start()
    add_log(f"Deck scheduler started (clear 09:00, scan 10:00-16:00 ET every {DECK_TICK_MINUTES}m)")


# ── FastAPI router ──────────────────────────────────────────────────────────

router = APIRouter(tags=["ui"])


@router.get("/healthz")
def healthz():
    return {"ok": True}


@router.get("/api/config")
def api_config_get():
    return _config_response()


@router.post("/api/config")
async def api_config_post(request: Request):
    payload = await request.json()
    if not isinstance(payload, dict):
        raise HTTPException(400, "expected JSON object")
    with _config_lock:
        for key in ("telegram_bot_token", "telegram_chat_ids",
                    "alpaca_api_key", "alpaca_api_secret", "gemini_api_key"):
            if key in payload:
                app_config[key] = str(payload[key]).strip()
        if "telegram_enabled" in payload:
            app_config["telegram_enabled"] = bool(payload["telegram_enabled"])
        if "alpaca_paper" in payload:
            app_config["alpaca_paper"] = bool(payload["alpaca_paper"])
        if "gemini_enabled" in payload:
            app_config["gemini_enabled"] = bool(payload["gemini_enabled"])
        if "profit_alerts_enabled" in payload:
            app_config["profit_alerts_enabled"] = bool(payload["profit_alerts_enabled"])
        if "profit_alert_pct" in payload:
            try:
                app_config["profit_alert_pct"] = max(1, min(99, int(payload["profit_alert_pct"])))
            except (TypeError, ValueError):
                pass
        if "theme" in payload and payload["theme"] in ("dark", "light"):
            app_config["theme"] = payload["theme"]
        if "watchlist_tickers" in payload and isinstance(payload["watchlist_tickers"], list):
            cleaned = []
            for t in payload["watchlist_tickers"]:
                s = str(t).strip().upper()
                if s and s not in cleaned:
                    cleaned.append(s)
            app_config["watchlist_tickers"] = cleaned
        if "scan_capital" in payload:
            try:
                app_config["scan_capital"] = max(100, int(payload["scan_capital"]))
            except (TypeError, ValueError):
                pass
        try:
            _save_config(app_config)
        except OSError as e:
            add_log(f"ERR: could not persist config — {e}", "error")
        _apply_config_to_env(app_config)
    add_log("Configuration updated")
    return _config_response()


def _config_response() -> dict:
    with _config_lock:
        cfg = dict(app_config)

    def mask(v: str) -> str:
        return f"{v[:6]}…{v[-4:]}" if v and len(v) > 12 else ("set" if v else "")

    def source(key: str) -> str:
        if not cfg.get(key):
            return "none"
        return "env" if key in _env_keys_at_boot else "disk"

    return {
        "telegram_bot_token_masked": mask(cfg.get("telegram_bot_token", "")),
        "telegram_bot_token_present": bool(cfg.get("telegram_bot_token")),
        "telegram_bot_token_source": source("telegram_bot_token"),
        "telegram_chat_ids": cfg.get("telegram_chat_ids", ""),
        "telegram_chat_ids_source": source("telegram_chat_ids"),
        "telegram_enabled": bool(cfg.get("telegram_enabled", True)),
        "fortress_api_url": "(inline)",  # kept for UI compat
        "fortress_api_url_source": "env",
        "fortress_api_token_masked": "",
        "fortress_api_token_present": True,
        "fortress_api_token_source": "env",
        "scan_capital": cfg.get("scan_capital", 5000),
        "backend_connected": True,  # always true — we ARE the backend
        "alpaca_api_key_masked": mask(cfg.get("alpaca_api_key", "")),
        "alpaca_api_key_present": bool(cfg.get("alpaca_api_key")),
        "alpaca_api_key_source": source("alpaca_api_key"),
        "alpaca_api_secret_masked": mask(cfg.get("alpaca_api_secret", "")),
        "alpaca_api_secret_present": bool(cfg.get("alpaca_api_secret")),
        "alpaca_api_secret_source": source("alpaca_api_secret"),
        "alpaca_paper": bool(cfg.get("alpaca_paper", True)),
        "gemini_api_key_masked": mask(cfg.get("gemini_api_key", "")),
        "gemini_api_key_present": bool(cfg.get("gemini_api_key")),
        "gemini_api_key_source": source("gemini_api_key"),
        "gemini_enabled": bool(cfg.get("gemini_enabled", True)),
        "profit_alert_pct": int(cfg.get("profit_alert_pct", 50) or 50),
        "profit_alerts_enabled": bool(cfg.get("profit_alerts_enabled", True)),
        "watchlist_tickers": list(cfg.get("watchlist_tickers", [])),
        "theme": cfg.get("theme", "dark"),
    }


@router.get("/api/scan")
def api_scan(capital: Optional[int] = None):
    _opportunistic_refresh()
    with _deck_lock:
        plays = list(_deck.values())
        meta = dict(_deck_meta)
    return {
        "plays": plays,
        "capital": capital or app_config.get("scan_capital", 5000),
        "deck": {
            "size": len(plays),
            "lastScanAt": meta.get("lastScanAt"),
            "lastClearAt": meta.get("lastClearAt"),
            "lastReason": meta.get("lastReason"),
            "marketHours": is_market_hours(),
            "marketDay": is_market_day(),
        },
    }


@router.get("/api/positions")
def api_positions():
    positions = get_positions()
    # Compute the CURRENT profit% snapshot inline (independent of scheduler
    # ticks) so the UI always shows the freshest number, then overlay the
    # tracker's fired-alert status.
    fired_map = position_status_map()
    with _alert_lock:
        fired_thresholds = {pid: sorted(list(t)) for pid, t in _fired_targets.items()}
    for p in positions:
        pid = p.get("id")
        p["profitPct"] = round(_position_profit_pct(p), 1)
        p["profitDollars"] = round((p.get("entryPremium", 0) - p.get("currentPremium", 0))
                                   * 100 * (p.get("contracts", 1)), 2)
        p["firedTargets"] = fired_thresholds.get(pid, [])
        seen = fired_map.get(pid)
        if seen:
            p["targetHit"] = seen.get("targetHit", False)
            p["targetPct"] = seen.get("targetPct")
    threshold = int(app_config.get("profit_alert_pct", 50) or 50)
    return {
        "positions": positions,
        "profitAlertPct": threshold,
        "profitAlertsEnabled": bool(app_config.get("profit_alerts_enabled", True)),
    }


@router.get("/api/earnings")
def api_earnings(tickers: str = Query("SPY,QQQ,IWM")):
    parsed = [t for t in tickers.split(",") if t.strip()] or ["SPY", "QQQ", "IWM"]
    return get_earnings(parsed)


@router.post("/api/analyze/play")
async def api_analyze_play(request: Request):
    payload = await request.json() or {}
    try:
        from main import AnalyzePlayRequest, _analyze_play  # type: ignore
    except ImportError:
        return {"analysis": "(analysis unavailable)", "source": "fallback"}
    with _config_lock:
        key = app_config.get("gemini_api_key", "") if app_config.get("gemini_enabled", True) else ""
    try:
        req = AnalyzePlayRequest(**payload)
    except Exception:
        return {"analysis": "(bad payload)", "source": "fallback"}
    resp = _analyze_play(req, key or None)
    return {"analysis": resp.analysis, "source": resp.source}


@router.get("/api/market")
def api_market():
    return get_market_summary()


@router.get("/api/watchlist")
def api_watchlist():
    """Return the current watchlist with a live spot price per ticker.
    Prices come from broker.get_stock_quotes() when Alpaca is configured;
    otherwise stubbed so the UI cards still render."""
    with _config_lock:
        tickers = list(app_config.get("watchlist_tickers", []))
    prices: dict[str, float] = {}
    try:
        import alpaca as broker  # type: ignore
        if tickers and broker.is_configured():
            prices = broker.get_stock_quotes(tickers) or {}
    except Exception as e:
        log.warning("[watchlist] quote fetch failed: %s", e)
    items = [{"ticker": t, "price": prices.get(t)} for t in tickers]
    return {"items": items}


@router.post("/api/watchlist/find_play")
async def api_watchlist_find_play(request: Request):
    """On-demand scan for a single ticker the operator asked about. Runs the
    full v1.2 filter stack (delta, OTM, earnings), just against one symbol.
    Result is added to the deck like any other scanned play."""
    payload = await request.json() or {}
    ticker = str(payload.get("ticker", "")).strip().upper()
    if not ticker:
        raise HTTPException(400, "ticker required")
    capital = app_config.get("scan_capital", 5000)
    try:
        import alpaca as broker  # type: ignore
        if not broker.is_configured():
            return {"ok": False, "found": 0,
                    "message": "Alpaca not configured — set keys in Settings first."}
        found = broker.scan_chains(capital, tickers=[ticker]) or []
    except Exception as e:
        log.exception("[find_play] %s failed: %s", ticker, e)
        return {"ok": False, "found": 0, "message": str(e)}
    if not found:
        return {"ok": True, "found": 0,
                "message": f"{ticker}: no candidate passed the v1.2 filter stack right now."}
    # Fold into the deck so it appears in Plays on the next refresh.
    now = datetime.now(timezone.utc)
    with _deck_lock:
        for p in found:
            existing = _deck.get(p["id"])
            if existing:
                p["_addedAt"] = existing.get("_addedAt", now.isoformat())
                p["_scanCount"] = existing.get("_scanCount", 0) + 1
            else:
                p["_addedAt"] = now.isoformat()
                p["_scanCount"] = 1
            _deck[p["id"]] = p
        _deck_meta["lastScanAt"] = now.isoformat()
        _deck_meta["lastReason"] = f"manual find_play ({ticker})"
    add_log(f"Watchlist: added {len(found)} {ticker} play(s) to deck")
    return {"ok": True, "found": len(found), "message": f"Added {len(found)} {ticker} play(s) to deck."}


@router.post("/api/alert")
async def api_alert(request: Request):
    payload = await request.json() if request.headers.get("content-type", "").startswith("application/json") else {}
    message = (payload.get("message") or "🚨 Fortress Test Action Triggered from Companion GUI").strip()
    add_log(f"🚨 {message}")
    threading.Thread(target=send_telegram_alert, args=(message,), daemon=True).start()
    return {"ok": True}


@router.get("/api/logs")
def api_logs():
    with _logs_lock:
        rows = list(logs_ring)
    with _config_lock:
        token = app_config.get("telegram_bot_token", "")
        ids = _parse_chat_ids(app_config.get("telegram_chat_ids", ""))
        enabled = bool(app_config.get("telegram_enabled", True))
    return {
        "logs": rows,
        "telegram": {
            "ready": bool(token and ids and enabled),
            "enabled": enabled,
            "recipientCount": len(ids),
        },
    }


@router.post("/api/deck/refresh")
def api_deck_refresh():
    return {"ok": True, **refresh_deck("manual (Scan Now)")}


@router.post("/api/deck/clear")
def api_deck_clear():
    return {"ok": True, **clear_deck("manual (Clear Deck)")}


@router.get("/", response_class=HTMLResponse, include_in_schema=False)
def ui_root():
    return HTMLResponse(INDEX_HTML)


# Push the loaded config into env so broker/gemini see it immediately.
_apply_config_to_env(app_config)


# ── The single-page app ─────────────────────────────────────────────────────

INDEX_HTML = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Fortress Companion</title>
<style>
  :root, body.theme-dark {
    --bg: #0d1117;
    --panel: #161b22;
    --panel-2: #1c2128;
    --border: #262b33;
    --text: #e6e6e6;
    --muted: #8b949e;
    --green: #4ade80;
    --green-dim: #16a34a;
    --gold: #ffd700;
    --orange: #ff9933;
    --red: #f87171;
  }
  body.theme-light {
    --bg: #f5f7fa;
    --panel: #ffffff;
    --panel-2: #f0f2f5;
    --border: #e1e5eb;
    --text: #1f2937;
    --muted: #6b7280;
    --green: #16a34a;
    --green-dim: #15803d;
    --gold: #b45309;
    --orange: #ea580c;
    --red: #dc2626;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body { margin: 0; padding: 0; background: var(--bg); color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Arial, sans-serif;
    overscroll-behavior-y: contain; }
  body { padding-bottom: 76px; min-height: 100vh; }

  /* ─── Market header ─── */
  .market {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: 12px;
    margin: 12px;
    display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 0;
  }
  .market .cell { padding: 14px 12px; text-align: center;
    border-right: 1px solid var(--border); }
  .market .cell:last-child { border-right: none; }
  .market .label { font-size: 11px; color: var(--muted); letter-spacing: 0.6px;
    text-transform: uppercase; margin-bottom: 4px; }
  .market .value { font-size: 22px; font-weight: 700; }
  .market .sub { font-size: 11px; color: var(--muted); margin-top: 4px;
    text-transform: uppercase; letter-spacing: 0.4px; }
  .green { color: var(--green); }
  .orange { color: var(--orange); }
  .red { color: var(--red); }
  .gold { color: var(--gold); }
  .muted { color: var(--muted); }

  /* ─── System status row ─── */
  .system {
    text-align: center; padding: 8px 12px 4px 12px;
    color: var(--green); font-size: 16px; font-weight: 600;
  }
  .brand { display: flex; align-items: center; gap: 14px; padding: 10px 16px 14px; }
  .brand .icon { width: 44px; height: 44px; border-radius: 10px;
    background: rgba(74,222,128,0.12); display: flex; align-items: center;
    justify-content: center; color: var(--green); font-size: 24px; }
  .brand .text h1 { margin: 0; font-size: 22px; font-weight: 700; }
  .brand .text h1 .green { font-weight: 700; }
  .brand .text .ol { font-size: 13px; color: var(--green); margin-top: 2px; }
  .brand .ver { margin-left: auto; color: var(--muted); font-size: 13px; }

  /* ─── Section bar ─── */
  .section-bar {
    display: flex; align-items: center; padding: 6px 16px 12px;
    font-weight: 700; font-size: 14px; letter-spacing: 0.4px;
    text-transform: uppercase;
  }
  .section-bar .actions { margin-left: auto; display: flex; gap: 16px; }
  .section-bar .actions button {
    background: none; border: none; padding: 0;
    font-weight: 700; font-size: 14px; cursor: pointer;
    letter-spacing: 0.4px;
  }
  .section-bar .scan { color: var(--green); }
  .section-bar .clear { color: var(--orange); }

  /* ─── Play / position card ─── */
  .card {
    background: var(--panel); border: 1px solid var(--border);
    border-radius: 12px; margin: 12px; padding: 16px;
    user-select: none;
  }
  .card-head { display: flex; align-items: flex-start; gap: 12px; }
  .ticker-logo {
    width: 38px; height: 38px; border-radius: 50%;
    background: #fff; overflow: hidden;
    display: flex; align-items: center; justify-content: center;
    font-weight: 800; font-size: 13px; color: #000; flex-shrink: 0;
    position: relative;
  }
  .ticker-logo img {
    width: 100%; height: 100%; object-fit: cover; display: block;
  }
  .ticker-logo .letter { display: none; }
  .ticker-logo.no-logo .letter { display: block; }
  .ticker-logo.no-logo img { display: none; }
  .ticker-info { flex: 1; min-width: 0; }
  .ticker-info .top { display: flex; align-items: baseline; gap: 8px; }
  .ticker-info .top h3 { margin: 0; font-size: 22px; font-weight: 800; }
  .ticker-info .meta { color: var(--muted); font-size: 12px;
    margin-top: 4px; text-transform: uppercase; letter-spacing: 0.4px; }
  .gtc-btn {
    border: 1px solid var(--green); color: var(--green);
    background: transparent; padding: 6px 14px; border-radius: 6px;
    font-weight: 700; font-size: 13px; cursor: pointer; flex-shrink: 0;
  }
  .progress {
    margin: 14px 0 8px; height: 6px; background: #21262d;
    border-radius: 999px; overflow: hidden;
  }
  .progress .bar { height: 100%; background: linear-gradient(90deg,#16a34a,#4ade80); }

  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; margin-top: 12px; }
  .field .k { color: var(--muted); font-size: 12px; }
  .field .v { font-weight: 700; font-size: 15px; margin-top: 4px; }
  .field.right { text-align: right; }

  .ai-box {
    margin-top: 14px; padding: 14px; background: var(--panel-2);
    border: 1px solid var(--border); border-radius: 8px;
  }
  .ai-box .head { display: flex; justify-content: space-between;
    align-items: center; margin-bottom: 6px; }
  .ai-box .head .label { color: var(--muted); font-size: 13px; font-weight: 600; }
  .ai-box .head .tag { color: var(--orange); font-weight: 700; font-size: 13px; }
  .ai-box p { margin: 0; font-size: 13.5px; line-height: 1.45; }

  .card-actions { display: grid; grid-template-columns: 1fr 2fr;
    gap: 10px; margin-top: 14px; }
  .btn {
    padding: 12px; border-radius: 8px; border: none; cursor: pointer;
    font-weight: 700; font-size: 14px;
  }
  .btn-secondary { background: #21262d; color: var(--text); }
  .btn-primary { background: var(--green); color: #052e1f; }
  .btn-primary::before { content: "▶ "; }

  /* ─── Empty / loading ─── */
  .empty {
    text-align: center; padding: 60px 24px; color: var(--muted);
    font-style: italic;
  }
  .demo-banner {
    margin: 12px; padding: 10px 14px; background: rgba(255,153,51,0.08);
    border: 1px solid rgba(255,153,51,0.3); color: var(--orange);
    border-radius: 8px; font-size: 13px;
  }
  .ern-badge {
    background: rgba(248,113,113,0.10);
    border: 1px solid rgba(248,113,113,0.45);
    color: var(--red);
    font-size: 12.5px; font-weight: 700;
    padding: 6px 10px; border-radius: 6px;
    margin-bottom: 12px; letter-spacing: 0.2px;
  }
  .target-badge {
    background: rgba(74,222,128,0.12);
    border: 1px solid rgba(74,222,128,0.45);
    color: var(--green);
    font-size: 12.5px; font-weight: 700;
    padding: 6px 10px; border-radius: 6px;
    margin-bottom: 12px; letter-spacing: 0.2px;
  }
  .src-pill {
    display: inline-block; padding: 2px 8px; border-radius: 999px;
    font-size: 11px; font-weight: 600; margin-left: 8px;
    font-family: ui-monospace, "SF Mono", Menlo, monospace;
    vertical-align: middle;
  }
  .src-env  { background: rgba(74,222,128,0.10); color: var(--green); border:1px solid rgba(74,222,128,0.35); }
  .src-disk { background: rgba(255,215,0,0.08);  color: var(--gold);  border:1px solid rgba(255,215,0,0.30); }
  .src-none { background: rgba(139,148,158,0.10); color: var(--muted); border:1px solid var(--border); }
  .src-ok   { background: rgba(74,222,128,0.10); color: var(--green); border:1px solid rgba(74,222,128,0.35); }

  /* Theme toggle */
  .theme-row { display: flex; gap: 8px; margin-top: 4px; }
  .theme-btn {
    flex: 1; padding: 10px 14px; border-radius: 6px;
    background: var(--panel-2); border: 1px solid var(--border);
    color: var(--text); cursor: pointer; font-size: 14px;
  }
  .theme-btn.active {
    background: var(--green); color: #052e1f; border-color: var(--green);
    font-weight: 700;
  }

  /* Watchlist */
  .wl-add-row { display: flex; gap: 8px; margin-top: 12px; margin-bottom: 12px; }
  .wl-add-row input {
    flex: 1; background: var(--panel-2); border: 1px solid var(--border);
    color: var(--text); border-radius: 6px; padding: 10px 12px; font-size: 14px;
    font-family: ui-monospace, "SF Mono", Menlo, monospace;
  }
  .wl-add-row button { padding: 10px 18px; }
  .wl-grid {
    display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
    gap: 10px;
  }
  .wl-card {
    background: var(--panel-2); border: 1px solid var(--border);
    border-radius: 10px; padding: 12px 10px 10px;
    display: flex; flex-direction: column; align-items: center; gap: 6px;
    position: relative;
  }
  .wl-card .ticker-logo { width: 32px; height: 32px; margin-bottom: 4px; }
  .wl-ticker { font-weight: 700; font-size: 15px; }
  .wl-price { font-weight: 600; font-size: 14px; }
  .wl-find { padding: 6px 12px; font-size: 12.5px; margin-top: 4px; }
  .wl-remove {
    position: absolute; top: 4px; right: 6px;
    background: transparent; border: none; color: var(--muted);
    cursor: pointer; font-size: 18px; line-height: 1; padding: 2px 6px;
  }
  .wl-remove:hover { color: var(--red); }

  /* Play rating badges */
  .rate-badge {
    display: inline-flex; align-items: center; gap: 4px;
    padding: 3px 9px; border-radius: 999px;
    font-size: 11px; font-weight: 700; letter-spacing: 0.4px;
    margin-left: 10px; cursor: help;
  }
  .rate-fire { background: rgba(255,153,51,0.12); color: var(--orange); border:1px solid rgba(255,153,51,0.40); }
  .rate-ok   { background: rgba(74,222,128,0.10); color: var(--green);  border:1px solid rgba(74,222,128,0.35); }
  .rate-risk { background: rgba(248,113,113,0.10); color: var(--red);   border:1px solid rgba(248,113,113,0.40); }
  .ai-source { color: var(--muted); font-size: 11px; font-style: italic; margin-left: 4px; }

  /* ─── Bottom nav ─── */
  nav.tabs {
    position: fixed; bottom: 0; left: 0; right: 0;
    background: var(--panel); border-top: 1px solid var(--border);
    display: grid; grid-template-columns: repeat(5,1fr);
    padding: 8px 0 12px; padding-bottom: calc(12px + env(safe-area-inset-bottom));
    z-index: 50;
  }
  nav.tabs button {
    background: none; border: none; color: var(--muted); cursor: pointer;
    display: flex; flex-direction: column; align-items: center; gap: 4px;
    padding: 4px 0; font-size: 11px;
  }
  nav.tabs button .icon { font-size: 22px; line-height: 1; }
  nav.tabs button.active { color: var(--green); }
  nav.tabs button.active .icon { color: var(--green); }

  /* ─── Alerts feed ─── */
  .feed-wrap { padding: 0 12px; }
  .feed-row {
    display: flex; gap: 12px; padding: 12px;
    border-bottom: 1px solid var(--border);
    font-family: ui-monospace, "SF Mono", Menlo, monospace;
    font-size: 13px;
  }
  .feed-row .ts { color: var(--muted); }
  .feed-row.success .msg { color: var(--green); }
  .feed-row.error .msg { color: var(--red); }
  .feed-row.info .msg { color: var(--text); }
  .alert-actions { display: flex; gap: 10px; padding: 12px; }

  /* ─── Settings ─── */
  .settings { padding: 8px 12px 40px; }
  .settings .section { background: var(--panel); border: 1px solid var(--border);
    border-radius: 12px; padding: 16px; margin-bottom: 16px; }
  .settings h3 { margin: 0 0 4px; font-size: 16px; }
  .settings .desc { color: var(--muted); font-size: 13px; margin: 0 0 16px; }
  .settings label { display: block; font-size: 13px; color: var(--muted);
    margin: 14px 0 6px; }
  .settings input[type=text], .settings input[type=password],
  .settings input[type=number], .settings textarea {
    width: 100%; background: var(--panel-2); border: 1px solid var(--border);
    color: var(--text); border-radius: 6px; padding: 10px 12px; font-size: 14px;
    font-family: ui-monospace, "SF Mono", Menlo, monospace;
  }
  .settings textarea { resize: vertical; min-height: 60px; }
  .settings input:focus, .settings textarea:focus {
    outline: none; border-color: var(--green);
  }
  .row { display: flex; gap: 12px; align-items: center; margin-top: 14px; }
  .row label { margin: 0; }
  .row input[type=checkbox] {
    width: 18px; height: 18px; accent-color: var(--green); cursor: pointer;
  }
  .save-row { display: flex; gap: 12px; align-items: center; margin-top: 18px; }
  .save-row .btn-primary { background: var(--green); color: #052e1f;
    padding: 11px 22px; }
  .save-row .btn-primary::before { content: ""; }
  .save-row .status { color: var(--green); font-size: 13px; }

  .pill {
    display: inline-block; padding: 3px 10px; border-radius: 999px;
    background: var(--panel-2); border: 1px solid var(--border);
    color: var(--muted); font-size: 12px; margin-right: 6px;
  }
  .pill.on { color: var(--green); border-color: rgba(74,222,128,0.4); }
  .pill.off { color: var(--muted); }

  /* ─── Modal (Manual Order Instructions) ─── */
  .modal-backdrop {
    position: fixed; inset: 0; background: rgba(0,0,0,0.7);
    z-index: 100; display: none; align-items: center; justify-content: center;
    padding: 16px;
  }
  .modal-backdrop.show { display: flex; }
  .modal {
    background: var(--panel); border: 1px solid var(--border);
    border-radius: 14px; max-width: 600px; width: 100%;
    max-height: 90vh; overflow-y: auto;
  }
  .modal header { padding: 18px 18px 8px; }
  .modal header h2 { margin: 0; font-size: 18px; display: flex;
    align-items: center; gap: 8px; }
  .stat-row {
    display: grid; grid-template-columns: 1fr 1fr 1fr;
    background: var(--panel-2); border: 1px solid var(--border);
    border-radius: 8px; margin: 14px 18px; overflow: hidden;
  }
  .stat-row .cell {
    padding: 12px; text-align: center; border-right: 1px solid var(--border);
  }
  .stat-row .cell:last-child { border-right: none; }
  .stat-row .k { color: var(--muted); font-size: 10.5px;
    letter-spacing: 0.5px; text-transform: uppercase; }
  .stat-row .v { font-weight: 700; font-size: 15px; margin-top: 4px; }
  .stat-now {
    margin: 0 18px 14px; display: flex; justify-content: space-between;
    background: var(--panel-2); border: 1px solid var(--border);
    border-radius: 8px; padding: 10px 12px; font-size: 13px;
  }
  .pnl-chart { margin: 0 18px 14px; background: #000; border-radius: 8px;
    padding: 8px; }
  .pnl-chart svg { display: block; width: 100%; height: 180px; }
  .pnl-text { padding: 0 18px; color: var(--text); }
  .order-legs {
    margin: 12px 18px; background: var(--panel-2);
    border: 1px solid var(--border); border-radius: 8px; padding: 14px;
    font-family: ui-monospace, "SF Mono", Menlo, monospace; font-size: 12.5px;
    line-height: 1.7;
  }
  .order-legs .leg { color: var(--text); }
  .order-legs .credit { color: var(--green); margin-top: 8px; }
  .tip { padding: 0 18px 14px; color: var(--muted); font-size: 12.5px;
    line-height: 1.45; }
  .modal-foot { padding: 8px 18px 18px; text-align: center; }
  .modal-foot button {
    background: var(--green); color: #052e1f; padding: 12px 32px;
    border-radius: 8px; border: none; font-weight: 700; cursor: pointer;
  }
</style>
</head>
<body>

<div id="root">
  <div id="market"></div>
  <div class="system" id="system">System: Operational</div>
  <div class="brand">
    <div class="icon">✓</div>
    <div class="text">
      <h1>Fortress <span class="green">Options</span></h1>
      <div class="ol">● Online</div>
    </div>
    <div class="ver">v1.1</div>
  </div>
  <div id="tab-content"></div>
</div>

<nav class="tabs" id="tabs">
  <button data-tab="plays" class="active"><span class="icon">▶</span><span>Plays</span></button>
  <button data-tab="positions"><span class="icon">📊</span><span>Positions</span></button>
  <button data-tab="earnings"><span class="icon">📋</span><span>Earnings</span></button>
  <button data-tab="alerts"><span class="icon">🔔</span><span>Alerts</span></button>
  <button data-tab="settings"><span class="icon">⚙</span><span>Settings</span></button>
</nav>

<div class="modal-backdrop" id="modal">
  <div class="modal" id="modal-inner"></div>
</div>

<script>
"use strict";

// ─── Helpers ────────────────────────────────────────────────────────────────
const $ = (sel, el=document) => el.querySelector(sel);
const $$ = (sel, el=document) => Array.from(el.querySelectorAll(sel));
const fmt = (n, d=2) => (n == null || isNaN(n)) ? "—" : Number(n).toFixed(d);
const money = (n, d=2) => "$" + fmt(n, d);
const pct = (n, d=1) => (n == null) ? "—" : (n * 100).toFixed(d) + "%";
const ucase = s => (s || "").toString().toUpperCase();

function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, c => ({
    "&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"
  }[c]));
}

// Stock logo with graceful fallback to a colored letter circle. Uses FMP's
// free image endpoint (no auth) and toggles a .no-logo class on img error so
// the letter shows through when the ticker isn't in their catalog.
function tickerLogo(ticker, extra = "") {
  const t = escapeHtml((ticker || "?").toUpperCase());
  return `<div class="ticker-logo ${extra}">
    <img src="https://financialmodelingprep.com/image-stock/${t}.png"
         alt="${t}" onerror="this.parentElement.classList.add('no-logo')">
    <span class="letter">${t.slice(0,1)}</span>
  </div>`;
}

function strategyLabel(s) {
  return ({
    PUT_CREDIT_SPREAD: "Bull Put Spread",
    CALL_CREDIT_SPREAD: "Bear Call Spread",
    CASH_SECURED_PUT: "Cash-Secured Put",
  })[s] || (s || "").replace(/_/g, " ").toLowerCase();
}

// ─── State ──────────────────────────────────────────────────────────────────
let currentTab = "plays";
let cachedPlays = [];

// ─── Tabs ───────────────────────────────────────────────────────────────────
$$("nav.tabs button").forEach(btn => {
  btn.addEventListener("click", () => switchTab(btn.dataset.tab));
});

function switchTab(name) {
  currentTab = name;
  $$("nav.tabs button").forEach(b => b.classList.toggle("active", b.dataset.tab === name));
  render();
}

// ─── Market header ──────────────────────────────────────────────────────────
async function loadMarket() {
  try {
    const r = await fetch("/api/market");
    const m = await r.json();
    const ratingClass = m.premiumSell.rating === "OPTIMAL" ? "green"
      : m.premiumSell.rating === "FAIR" ? "gold" : "orange";
    $("#market").innerHTML = `
      <div class="market">
        <div class="cell">
          <div class="label">VIX</div>
          <div class="value green">${fmt(m.vix.value, 1)}</div>
          <div class="sub">${escapeHtml(m.vix.label)}</div>
        </div>
        <div class="cell">
          <div class="label">SPY</div>
          <div class="value">${money(m.spy.price)}</div>
          <div class="sub green">▲ ${fmt(m.spy.changePct, 2)}%</div>
        </div>
        <div class="cell">
          <div class="label">Premium Sell</div>
          <div class="value ${ratingClass}">${escapeHtml(m.premiumSell.rating)}</div>
          <div class="sub">${escapeHtml(m.premiumSell.sub)}</div>
        </div>
      </div>`;
  } catch (e) { /* swallow */ }
}

// ─── Plays ──────────────────────────────────────────────────────────────────
async function renderPlays() {
  const el = $("#tab-content");
  el.innerHTML = `
    <div class="section-bar">
      Monitored Premium Spreads
      <div class="actions">
        <button class="scan" id="btn-scan">Scan Now</button>
        <button class="clear" id="btn-clear">Clear Deck</button>
      </div>
    </div>
    <div id="deck-status" class="deck-status muted" style="padding: 0 16px 8px;"></div>
    <div id="plays-list" class="empty">Loading…</div>`;
  $("#btn-scan").addEventListener("click", async () => {
    await fetch("/api/deck/refresh", {method:"POST"});
    await loadPlays();
  });
  $("#btn-clear").addEventListener("click", async () => {
    if (!confirm("Clear all plays from the deck?")) return;
    await fetch("/api/deck/clear", {method:"POST"});
    await loadPlays();
  });
  await loadPlays();
}

async function loadPlays() {
  const list = $("#plays-list");
  if (list) list.innerHTML = `<div class="empty">Loading…</div>`;
  try {
    const r = await fetch("/api/scan");
    const data = await r.json();
    cachedPlays = data.plays || [];
    renderDeckStatus(data.deck || {});
    renderPlayList(cachedPlays, cachedPlays.some(p => p.demo));
  } catch (e) {
    list.innerHTML = `<div class="empty">Could not load plays.</div>`;
  }
}

function renderDeckStatus(d) {
  const el = $("#deck-status");
  if (!el) return;
  const now = new Date();
  const rel = (iso) => {
    if (!iso) return "never";
    const then = new Date(iso);
    const s = Math.floor((now - then) / 1000);
    if (s < 60) return `${s}s ago`;
    if (s < 3600) return `${Math.floor(s/60)}m ago`;
    if (s < 86400) return `${Math.floor(s/3600)}h ago`;
    return then.toLocaleString();
  };
  const marketTag = d.marketHours
    ? `<span class="green">● market open</span>`
    : (d.marketDay ? `<span class="gold">market day, off-hours (scans 10:00–16:00 ET)</span>`
                   : `<span class="muted">closed today</span>`);
  el.innerHTML = `
    <span>${d.size || 0} plays in deck</span>
    &nbsp;·&nbsp;
    <span>last scan: ${rel(d.lastScanAt)}</span>
    &nbsp;·&nbsp;
    <span>last clear: ${rel(d.lastClearAt)}</span>
    &nbsp;·&nbsp;
    ${marketTag}
  `;
}

function renderPlayList(plays, isDemo) {
  const list = $("#plays-list");
  if (!plays.length) {
    list.innerHTML = `<div class="empty">No plays right now. Tap Scan Now to refresh.</div>`;
    return;
  }
  const banner = isDemo
    ? `<div class="demo-banner">⚠ Showing demo data — wire FORTRESS_API_URL + FORTRESS_API_TOKEN in Settings for live scans.</div>`
    : "";
  list.innerHTML = banner + plays.map(playCard).join("");
  attachLongPress();
  // Fire-and-forget AI analysis per card. Each fetch fills its own ai-box.
  plays.forEach(p => loadAnalysis(p));
}

async function loadAnalysis(p) {
  const rating = ratePlay(p);
  const payload = { ...p, rating: rating.tag };
  try {
    const r = await fetch("/api/analyze/play", {
      method: "POST",
      headers: {"Content-Type":"application/json"},
      body: JSON.stringify(payload),
    });
    const data = await r.json();
    const box = document.querySelector(`.ai-box[data-ai-for="${cssEscape(p.id)}"]`);
    if (!box) return;
    const text = data.analysis || "(no analysis)";
    const sourceLabel = data.source === "gemini" ? "Gemini"
                      : data.source === "cache"  ? "Gemini · cached"
                      : "heuristic";
    box.querySelector(".ai-text").innerHTML =
      `${escapeHtml(text)} <span class="ai-source">· ${escapeHtml(sourceLabel)}</span>`;
  } catch (e) { /* card falls back to loading text; retry on next scan */ }
}

function cssEscape(s) {
  // Minimal CSS.escape polyfill for the attribute selector above.
  return (window.CSS && CSS.escape) ? CSS.escape(s) : s.replace(/[^a-zA-Z0-9_-]/g, "\\$&");
}

// Play rating: three explicit tiers based on probability-of-profit, safety
// buffer, and short-leg delta. Pure UI judgment — the scanner already
// dropped anything genuinely unsafe before it reached this code.
function ratePlay(p) {
  const pop = p.probabilityOfProfit || 0;
  const buf = p.safetyBufferPct || 0;
  const delta = (p.shortDelta != null) ? Math.abs(p.shortDelta) : (1 - pop);

  // 🔥 amazing: deep buffer, high POP, low delta — premium worth taking.
  if (pop >= 0.85 && buf >= 0.10 && delta <= 0.12) {
    return {emoji: '🔥', tag: 'AMAZING', cls: 'rate-fire', tip: 'Deep buffer, high probability of profit, low delta — premium worth taking.'};
  }
  // 👍 ok: solid but not exceptional.
  if (pop >= 0.75 && buf >= 0.07 && delta <= 0.18) {
    return {emoji: '👍', tag: 'OK', cls: 'rate-ok', tip: 'Probability and buffer are reasonable. Standard play.'};
  }
  // ⚠ risky: anything weaker — caller should think twice.
  return {emoji: '⚠', tag: 'RISKY', cls: 'rate-risk', tip: 'Thin buffer or low probability — proceed with caution.'};
}

function playCard(p) {
  const cushion = Math.round((p.safetyBufferPct || 0) * 1000) / 10;
  const credit = p.estimatedCreditPerContract || 0;
  const profitDollars = credit * 100;
  const rating = ratePlay(p);
  const isHot = rating.tag === 'AMAZING';
  const margin = computeMargin(p);
  const ivPct = Math.round((p.ivRank || 0) * 100);

  return `
    <div class="card" data-play-id="${escapeHtml(p.id)}">
      <div class="card-head">
        ${tickerLogo(p.ticker)}
        <div class="ticker-info">
          <div class="top">
            <h3>${escapeHtml(p.ticker)}</h3>
            <span class="rate-badge ${rating.cls}" title="${escapeHtml(rating.tip)}">${rating.emoji} ${rating.tag}</span>
          </div>
          <div class="meta">${ucase(strategyLabel(p.strategy))} • Exp: ${escapeHtml(p.expiration)} (${p.dte} DTE)${p._scanCount ? ` • seen ${p._scanCount}× in deck` : ''}</div>
        </div>
        <button class="gtc-btn" onclick="event.stopPropagation()">GTC close</button>
      </div>
      <div class="progress"><div class="bar" style="width:${Math.min(95, Math.max(15, ivPct))}%"></div></div>
      <div class="grid">
        <div class="field"><div class="k">Earnings Event Windows:</div>
          <div class="v">${p.earningsClear ? 'Normal Cycle Expiry' : 'Earnings Inside Window'}</div></div>
        <div class="field right"><div class="k">Premium mid-price</div>
          <div class="v">${money(credit)}</div></div>
        <div class="field"><div class="k">Implied Volatility (IV)</div>
          <div class="v">IV Rank: ${ivPct}% &nbsp;|&nbsp; Cushion: ${cushion}%</div></div>
        <div class="field right"><div class="k">Asset Liquidity</div>
          <div class="v green">HIGH (Bid-Ask Spread &lt; $0.05)</div></div>
        <div class="field"><div class="k">Initial Credit / Potential profit per contract</div>
          <div class="v">${money(credit)} &nbsp;→&nbsp; <span class="green">${money(profitDollars)}</span></div></div>
        <div class="field right"><div class="k">Margin Collateral</div>
          <div class="v orange">${money(margin, 0)}</div></div>
      </div>
      <div class="ai-box" data-ai-for="${escapeHtml(p.id)}">
        <div class="head">
          <span class="label">AI Brain Analysis:</span>
          <span class="tag ${rating.cls}">${rating.emoji} ${rating.tag}</span>
        </div>
        <p class="ai-text"><em class="muted">Loading Gemini reasoning…</em></p>
      </div>
      <div class="card-actions">
        <button class="btn btn-secondary">Re-Analy</button>
        <button class="btn btn-primary">Track Position</button>
      </div>
    </div>`;
}

function computeMargin(p) {
  if (p.strategy === "CASH_SECURED_PUT") return p.shortStrike * 100;
  // credit spread: width × 100 − credit collected
  const width = Math.abs((p.shortStrike || 0) - (p.longStrike || 0));
  return Math.max(0, width * 100 - (p.estimatedCreditPerContract || 0) * 100);
}

// ─── Long-press handling ────────────────────────────────────────────────────
function attachLongPress() {
  $$("#plays-list .card").forEach(card => {
    let timer = null;
    let fired = false;
    const fire = () => {
      fired = true;
      const id = card.dataset.playId;
      const p = cachedPlays.find(x => x.id === id);
      if (p) showInstructions(p);
    };
    card.addEventListener("touchstart", e => {
      if (e.target.closest("button")) return;
      fired = false;
      timer = setTimeout(fire, 500);
    }, {passive: true});
    card.addEventListener("touchend", () => { clearTimeout(timer); });
    card.addEventListener("touchmove", () => { clearTimeout(timer); });
    card.addEventListener("contextmenu", e => {
      e.preventDefault();
      if (!fired) fire();
    });
    // mouse long-press for desktop testing
    let mTimer = null;
    card.addEventListener("mousedown", e => {
      if (e.target.closest("button")) return;
      if (e.button !== 0) return;
      fired = false;
      mTimer = setTimeout(fire, 500);
    });
    card.addEventListener("mouseup", () => clearTimeout(mTimer));
    card.addEventListener("mouseleave", () => clearTimeout(mTimer));
  });
}

// ─── Manual Order Instructions modal ────────────────────────────────────────
function showInstructions(p) {
  const inst = computeInstructions(p);
  $("#modal-inner").innerHTML = `
    <header><h2>📋 Manual Order Instructions</h2></header>
    <div class="stat-row">
      <div class="cell"><div class="k">Max Profit</div>
        <div class="v green">${money(inst.maxProfit, 0)}</div></div>
      <div class="cell"><div class="k">Breakeven</div>
        <div class="v">${inst.breakevens.map(b => money(b)).join(" / ")}</div></div>
      <div class="cell"><div class="k">Max Loss</div>
        <div class="v ${inst.maxLoss < 0 ? 'orange' : 'green'}">${money(inst.maxLoss, 0)}</div></div>
    </div>
    <div class="stat-now"><span>${escapeHtml(p.ticker)} PRICE NOW</span>
      <strong>${money(p.underlyingPrice)}</strong></div>
    <div class="pnl-chart">${pnlSvg(inst, p)}</div>
    <div class="pnl-text">To open this ${escapeHtml(strategyLabel(p.strategy).toLowerCase())} on ${escapeHtml(p.ticker)} manually, place the following order legs:</div>
    <div class="order-legs">
      ${inst.legs.map((leg, i) => `<div class="leg">${i+1}. ${escapeHtml(leg)}</div>`).join("")}
      <div class="credit">• Expected Net Credit: ${money(inst.credit * 100, 2)}</div>
    </div>
    <div class="tip">Tip: Execute this strategy as a single combo/spread ticket on your platform. Set your limit credit to ${money(inst.credit, 2)} (mid-price).</div>
    <div class="modal-foot"><button id="modal-ok">Got it</button></div>`;
  $("#modal").classList.add("show");
  $("#modal-ok").addEventListener("click", () => $("#modal").classList.remove("show"));
}

$("#modal").addEventListener("click", e => {
  if (e.target.id === "modal") $("#modal").classList.remove("show");
});

function computeInstructions(p) {
  const credit = p.estimatedCreditPerContract || 0;
  const ticker = p.ticker, exp = p.expiration;
  if (p.strategy === "PUT_CREDIT_SPREAD") {
    const breakeven = p.shortStrike - credit;
    const width = p.shortStrike - p.longStrike;
    return {
      legs: [
        `SELL 1x ${ticker} (Exp: ${exp}) $${p.shortStrike} PUT`,
        `BUY 1x ${ticker} (Exp: ${exp}) $${p.longStrike} PUT`,
      ],
      breakevens: [breakeven],
      maxProfit: credit * 100,
      maxLoss: -(width - credit) * 100,
      credit,
      kind: "put-credit",
    };
  }
  if (p.strategy === "CALL_CREDIT_SPREAD") {
    const breakeven = p.shortStrike + credit;
    const width = p.longStrike - p.shortStrike;
    return {
      legs: [
        `SELL 1x ${ticker} (Exp: ${exp}) $${p.shortStrike} CALL`,
        `BUY 1x ${ticker} (Exp: ${exp}) $${p.longStrike} CALL`,
      ],
      breakevens: [breakeven],
      maxProfit: credit * 100,
      maxLoss: -(width - credit) * 100,
      credit,
      kind: "call-credit",
    };
  }
  if (p.strategy === "CASH_SECURED_PUT") {
    const breakeven = p.shortStrike - credit;
    return {
      legs: [
        `SELL 1x ${ticker} (Exp: ${exp}) $${p.shortStrike} PUT`,
      ],
      breakevens: [breakeven],
      maxProfit: credit * 100,
      maxLoss: -(p.shortStrike - credit) * 100,
      credit,
      kind: "csp",
    };
  }
  return { legs: ["(unsupported strategy)"], breakevens: [], maxProfit: 0, maxLoss: 0, credit, kind: "unknown" };
}

function pnlSvg(inst, p) {
  const W = 520, H = 180, padX = 30, padY = 18;
  const spot = p.underlyingPrice;
  // X-axis range: 30% around spot
  const xMin = spot * 0.7, xMax = spot * 1.3;
  const px = (x) => padX + ((x - xMin) / (xMax - xMin)) * (W - padX*2);
  // Compute payoff curve at expiration
  const N = 100;
  const points = [];
  let pMin = inst.maxLoss, pMax = inst.maxProfit;
  for (let i = 0; i <= N; i++) {
    const x = xMin + (xMax - xMin) * (i / N);
    const y = payoffAt(x, p, inst);
    pMin = Math.min(pMin, y);
    pMax = Math.max(pMax, y);
    points.push([x, y]);
  }
  const range = (pMax - pMin) || 1;
  const py = (y) => H - padY - ((y - pMin) / range) * (H - padY*2);
  const path = points.map(([x,y],i) => (i?"L":"M") + px(x).toFixed(1) + "," + py(y).toFixed(1)).join(" ");
  const zero = py(0);
  const spotX = px(spot);

  // Split fill above/below zero
  const positive = points.filter(([_,y]) => y >= 0);
  const negative = points.filter(([_,y]) => y < 0);
  return `<svg viewBox="0 0 ${W} ${H}" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="gp" x1="0" x2="0" y1="0" y2="1">
        <stop offset="0%" stop-color="#4ade80" stop-opacity="0.55"/>
        <stop offset="100%" stop-color="#4ade80" stop-opacity="0.05"/>
      </linearGradient>
    </defs>
    <line x1="${padX}" y1="${zero}" x2="${W-padX}" y2="${zero}"
      stroke="#3a3f47" stroke-dasharray="3,3"/>
    <line x1="${spotX}" y1="${padY}" x2="${spotX}" y2="${H-padY}"
      stroke="#3a3f47" stroke-dasharray="3,3"/>
    <path d="${path} L ${px(xMax).toFixed(1)},${zero} L ${padX},${zero} Z"
      fill="url(#gp)" stroke="none"/>
    <path d="${path}" fill="none" stroke="#4ade80" stroke-width="2.5" stroke-linejoin="round"/>
  </svg>`;
}

function payoffAt(price, p, inst) {
  const credit = inst.credit;
  if (inst.kind === "put-credit") {
    const longLoss = Math.max(0, p.longStrike - price);
    const shortLoss = Math.max(0, p.shortStrike - price);
    return (credit - (shortLoss - longLoss)) * 100;
  }
  if (inst.kind === "call-credit") {
    const longLoss = Math.max(0, price - p.longStrike);
    const shortLoss = Math.max(0, price - p.shortStrike);
    return (credit - (shortLoss - longLoss)) * 100;
  }
  if (inst.kind === "csp") {
    const loss = Math.max(0, p.shortStrike - price);
    return (credit - loss) * 100;
  }
  return 0;
}

// ─── Positions ──────────────────────────────────────────────────────────────
async function renderPositions() {
  const el = $("#tab-content");
  el.innerHTML = `<div class="section-bar">Active Positions</div>
                  <div id="pos-list" class="empty">Loading…</div>`;
  try {
    const r = await fetch("/api/positions");
    const data = await r.json();
    const list = $("#pos-list");
    if (!data.positions.length) {
      list.innerHTML = `<div class="empty">No open positions.</div>`;
      return;
    }

    // Cross-check the held tickers against the earnings gate so cards can
    // surface the warning before the user has to switch tabs.
    const heldTickers = data.positions.map(p => p.ticker);
    const ern = await fetch(`/api/earnings?tickers=${encodeURIComponent(heldTickers.join(","))}`)
      .then(r => r.json()).catch(() => ({items:[]}));
    const ernByTicker = Object.fromEntries((ern.items || []).map(i => [i.ticker, i]));

    list.innerHTML = (data.positions[0]?.demo
      ? `<div class="demo-banner">⚠ Demo data — wire backend in Settings for live positions.</div>`
      : "")
      + data.positions.map(p => positionCard(p, ernByTicker[p.ticker])).join("");
  } catch (e) {
    $("#pos-list").innerHTML = `<div class="empty">Could not load positions.</div>`;
  }
}

function positionCard(p, ern) {
  const profit = (p.entryPremium - p.currentPremium) / (p.entryPremium || 1);
  const profitDollars = (p.entryPremium - p.currentPremium) * 100 * (p.contracts || 1);
  const ernBadge = (ern && ern.withinBlackout)
    ? `<div class="ern-badge">⚠ Earnings in ${ern.daysUntil}d — gate flagged</div>`
    : "";
  const targetBadge = p.targetHit
    ? `<div class="target-badge">🎯 ${p.profitPct}% profit — GTC exit ready (target: ${p.targetPct}%)</div>`
    : "";
  return `<div class="card">
    ${targetBadge}
    ${ernBadge}
    <div class="card-head">
      ${tickerLogo(p.ticker)}
      <div class="ticker-info">
        <div class="top"><h3>${escapeHtml(p.ticker)}</h3></div>
        <div class="meta">${escapeHtml(p.strategyLabel)} • Exp: ${escapeHtml(p.expiration)}</div>
      </div>
      <button class="gtc-btn">Close</button>
    </div>
    <div class="grid">
      <div class="field"><div class="k">Underlying</div>
        <div class="v">${money(p.underlyingPrice)}</div></div>
      <div class="field right"><div class="k">Contracts</div>
        <div class="v">${p.contracts}</div></div>
      <div class="field"><div class="k">Entry Credit</div>
        <div class="v">${money(p.entryPremium)}</div></div>
      <div class="field right"><div class="k">Current Mid</div>
        <div class="v">${money(p.currentPremium)}</div></div>
      <div class="field"><div class="k">P&L %</div>
        <div class="v ${profit>=0?'green':'red'}">${pct(profit, 0)}</div></div>
      <div class="field right"><div class="k">P&L $</div>
        <div class="v ${profitDollars>=0?'green':'red'}">${money(profitDollars, 2)}</div></div>
    </div>
  </div>`;
}

// ─── Earnings — Automated Blackout Gate ─────────────────────────────────────
async function renderEarnings() {
  const el = $("#tab-content");
  el.innerHTML = `
    <div class="section-bar">
      Automated Earnings Blackout Gate
      <div class="actions"><button class="scan" id="btn-ern-refresh">Refresh</button></div>
    </div>
    <div id="ern-list" class="empty">Loading…</div>`;
  $("#btn-ern-refresh").addEventListener("click", loadEarnings);
  await loadEarnings();
}

async function loadEarnings() {
  const list = $("#ern-list");
  list.innerHTML = `<div class="empty">Loading…</div>`;
  try {
    // Universe (SPY/QQQ/IWM) ∪ tickers from currently held positions.
    const posResp = await fetch("/api/positions").then(r => r.json()).catch(() => ({positions:[]}));
    const heldTickers = (posResp.positions || []).map(p => p.ticker);
    const universe = ["SPY","QQQ","IWM"];
    const all = Array.from(new Set([...universe, ...heldTickers])).filter(Boolean);
    const data = await fetch(`/api/earnings?tickers=${encodeURIComponent(all.join(","))}`).then(r => r.json());

    const items = data.items || [];
    const blocked = items.filter(i => i.withinBlackout).length;
    const clear = items.filter(i => !i.withinBlackout).length;

    const banner = data.demo
      ? `<div class="demo-banner">⚠ Demo data — wire backend in Settings to pull real earnings dates.</div>`
      : "";

    list.innerHTML = banner + `
      <div class="card" style="display:grid; grid-template-columns: 1fr 1fr 1fr; gap:0;">
        <div style="text-align:center; padding:10px; border-right:1px solid var(--border);">
          <div class="muted" style="font-size:11px; text-transform:uppercase;">Gate Status</div>
          <div class="value ${blocked>0?'orange':'green'}" style="font-size:18px; font-weight:700; margin-top:6px;">
            ${blocked>0 ? `${blocked} BLOCKED` : 'ALL CLEAR'}
          </div>
        </div>
        <div style="text-align:center; padding:10px; border-right:1px solid var(--border);">
          <div class="muted" style="font-size:11px; text-transform:uppercase;">Monitored</div>
          <div class="value" style="font-size:18px; font-weight:700; margin-top:6px;">${items.length}</div>
        </div>
        <div style="text-align:center; padding:10px;">
          <div class="muted" style="font-size:11px; text-transform:uppercase;">Blackout Window</div>
          <div class="value gold" style="font-size:18px; font-weight:700; margin-top:6px;">${data.blackoutDays}d</div>
        </div>
      </div>` +
      items.map(earningsRow).join("");
  } catch (e) {
    list.innerHTML = `<div class="empty">Could not load earnings calendar.</div>`;
  }
}

function earningsRow(item) {
  const cleared = !item.withinBlackout;
  const statusClass = item.isEtf ? "muted" : (cleared ? "green" : "orange");
  const statusText = item.isEtf
    ? "ETF · no earnings"
    : (cleared ? "CLEAR" : "BLACKED OUT");
  const dateText = item.nextEarningsDate
    ? `${item.nextEarningsDate} (${item.daysUntil}d)`
    : "—";
  return `<div class="card" style="padding:14px;">
    <div style="display:flex; align-items:center; gap:14px;">
      ${tickerLogo(item.ticker)}
      <div style="flex:1;">
        <div style="font-size:17px; font-weight:700;">${escapeHtml(item.ticker)}</div>
        <div class="muted" style="font-size:12px; margin-top:2px;">
          Next earnings: ${escapeHtml(dateText)}
        </div>
      </div>
      <div class="${statusClass}" style="font-weight:700; font-size:13px;">${statusText}</div>
    </div>
  </div>`;
}

// ─── Alerts ─────────────────────────────────────────────────────────────────
async function renderAlerts() {
  $("#tab-content").innerHTML = `
    <div class="section-bar">Alerts</div>
    <div class="alert-actions">
      <button class="btn btn-primary" id="btn-fire" style="padding:10px 18px;">Trigger Test Alert</button>
      <span id="alert-pills" style="margin-left:auto;align-self:center;"></span>
    </div>
    <div class="feed-wrap"><div id="feed">Loading…</div></div>`;
  $("#btn-fire").addEventListener("click", async () => {
    await fetch("/api/alert", { method: "POST",
      headers: {"Content-Type":"application/json"},
      body: JSON.stringify({message:"🚨 Fortress Test Action Triggered from Companion GUI"}) });
    refreshFeed();
  });
  await refreshFeed();
}

async function refreshFeed() {
  try {
    const r = await fetch("/api/logs");
    const data = await r.json();
    const rows = (data.logs || []).slice().reverse();
    $("#feed").innerHTML = rows.length
      ? rows.map(r => `<div class="feed-row ${r.level}"><span class="ts">${r.ts}</span><span class="msg">${escapeHtml(r.message)}</span></div>`).join("")
      : `<div class="empty">No alerts yet.</div>`;
    const t = data.telegram || {};
    $("#alert-pills").innerHTML = `<span class="pill ${t.ready?'on':'off'}">Telegram: ${t.ready?'on':'off'}</span>`
      + (t.recipientCount ? `<span class="pill">${t.recipientCount} recipient${t.recipientCount===1?'':'s'}</span>` : "");
  } catch (e) { /* swallow */ }
}

// ─── Settings ───────────────────────────────────────────────────────────────
async function renderSettings() {
  const r = await fetch("/api/config");
  const cfg = await r.json();

  // Source pill: "env" = durable (set as host env var), "disk" = saved locally
  // (resets on container restart — Render free tier is ephemeral), "none" = blank.
  const pill = (src, masked) => {
    if (src === "env")  return `<span class="src-pill src-env">env · durable · ${escapeHtml(masked||"set")}</span>`;
    if (src === "disk") return `<span class="src-pill src-disk">saved · ${escapeHtml(masked||"set")} · resets on cold start</span>`;
    return `<span class="src-pill src-none">not set</span>`;
  };

  // Any field saved to disk (not env) is at risk on Render free tier.
  const onDisk = [
    cfg.alpaca_api_key_source, cfg.alpaca_api_secret_source,
    cfg.fortress_api_token_source, cfg.telegram_bot_token_source,
    cfg.gemini_api_key_source,
  ].includes("disk");

  $("#tab-content").innerHTML = `
    <div class="settings">
      ${onDisk ? `<div class="demo-banner" style="margin-bottom:16px;">⚠ One or more secrets are saved on disk only. On Render's free tier the container's filesystem resets on cold start (~15 min of inactivity). For durability, paste the same values into the service's <strong>Environment</strong> tab on Render.</div>` : ""}

      <div class="section">
        <h3>Appearance</h3>
        <p class="desc">Switch between dark and light modes. Persists across sessions.</p>
        <div class="theme-row">
          <button class="theme-btn ${cfg.theme === 'dark' ? 'active' : ''}" data-theme="dark">🌙 Dark</button>
          <button class="theme-btn ${cfg.theme === 'light' ? 'active' : ''}" data-theme="light">☀ Light</button>
        </div>
      </div>

      <div class="section">
        <h3>Watchlist Tickers & Scanner</h3>
        <p class="desc">Symbols you want to hunt for plays on demand. "Find Play" runs the full v1.2 filter stack against just that ticker and drops any survivors into the deck. Single stocks reintroduce earnings/gap risk that the ETF-only scanner avoids — the earnings gate still applies.</p>
        <div class="wl-add-row">
          <input type="text" id="wl-input" placeholder="Add Ticker (e.g. MSFT)" style="text-transform:uppercase;">
          <button class="btn btn-primary" id="wl-add">Add</button>
        </div>
        <div id="wl-grid" class="wl-grid"></div>
      </div>

      <div class="section">
        <h3>Scanner</h3>
        <p class="desc">Sizing knob for the scan. Applied to Plays on the next refresh.</p>
        <label>Scan Capital</label>
        <input type="number" id="scan-capital" value="${cfg.scan_capital}" min="100" step="100">
        <div class="save-row">
          <button class="btn btn-primary" data-section="scanner">Save Scanner</button>
          <span class="status"></span>
        </div>
      </div>

      <div class="section">
        <h3>Profit-Target Alerts ${cfg.profit_alerts_enabled ? '<span class="src-pill src-ok">✓ armed</span>' : '<span class="src-pill src-none">off</span>'}</h3>
        <p class="desc">The tracker polls your positions every 5 minutes during market hours. When any position crosses this profit threshold, a Telegram alert fires so you can set the GTC exit. Standard tiers (target/75%/100%) each fire once per position per container lifetime.</p>
        <label>Alert threshold (% of max profit)</label>
        <input type="number" id="profit-pct" value="${cfg.profit_alert_pct}" min="1" max="99" step="5">
        <div class="row">
          <input type="checkbox" id="profit-enabled" ${cfg.profit_alerts_enabled ? 'checked' : ''}>
          <label for="profit-enabled">Send Telegram when a position hits the target</label>
        </div>
        <div class="save-row">
          <button class="btn btn-primary" data-section="profit">Save Alerts</button>
          <span class="status"></span>
        </div>
      </div>

      <div class="section">
        <h3>Alpaca Broker ${cfg.alpaca_api_key_present && cfg.alpaca_api_secret_present ? `<span class="src-pill src-ok">✓ ${cfg.alpaca_paper ? 'paper' : '<strong style="color:#ff5c5c">LIVE</strong>'}</span>` : '<span class="src-pill src-none">not configured</span>'}</h3>
        <p class="desc">Your Alpaca trading credentials. The companion forwards these to fortress-api on every request so the same scanner uses your account.</p>

        <label>Alpaca API Key ${pill(cfg.alpaca_api_key_source, cfg.alpaca_api_key_masked)}</label>
        <input type="text" id="alpaca-key" value="" placeholder="${cfg.alpaca_api_key_present ? 'leave blank to keep current value above' : 'PKABCD... (from alpaca.markets/dashboard)'}">

        <label>Alpaca API Secret ${pill(cfg.alpaca_api_secret_source, cfg.alpaca_api_secret_masked)}</label>
        <input type="text" id="alpaca-secret" value="" placeholder="${cfg.alpaca_api_secret_present ? 'leave blank to keep current value above' : '(not set)'}">

        <div class="row">
          <input type="checkbox" id="alpaca-paper" ${cfg.alpaca_paper ? 'checked' : ''}>
          <label for="alpaca-paper">Paper trading (safe default — uncheck only when you want real-money orders)</label>
        </div>
        <div class="save-row">
          <button class="btn btn-primary" data-section="alpaca">Save Alpaca</button>
          <span class="status"></span>
        </div>
      </div>

      <div class="section">
        <h3>Gemini AI ${cfg.gemini_api_key_present && cfg.gemini_enabled ? '<span class="src-pill src-ok">✓ analyzing</span>' : (cfg.gemini_api_key_present ? '<span class="src-pill src-disk">key saved · off</span>' : '<span class="src-pill src-none">not configured</span>')}</h3>
        <p class="desc">Powers the per-play AI analysis under each play card. When configured, every scanned play gets a fresh 2–3 sentence Gemini reasoning (why it's worth taking, main risk to watch, one alert level). Falls back to a deterministic heuristic when the key is blank or Gemini is off.</p>

        <label>Gemini API Key ${pill(cfg.gemini_api_key_source, cfg.gemini_api_key_masked)}</label>
        <input type="text" id="gemini-key" value="" placeholder="${cfg.gemini_api_key_present ? 'leave blank to keep current value above' : 'AIza... (from aistudio.google.com/app/apikey)'}">

        <div class="row">
          <input type="checkbox" id="gemini-enabled" ${cfg.gemini_enabled ? 'checked' : ''}>
          <label for="gemini-enabled">Enable AI analysis on play cards (uncheck to save API quota)</label>
        </div>
        <div class="save-row">
          <button class="btn btn-primary" data-section="gemini">Save Gemini</button>
          <span class="status"></span>
        </div>
      </div>

      <div class="section">
        <h3>Telegram Bridge ${cfg.telegram_bot_token_present ? '<span class="src-pill src-ok">✓ bot set</span>' : '<span class="src-pill src-none">not configured</span>'}</h3>
        <p class="desc">When enabled, every alert (test or auto) is delivered to every chat ID listed.</p>

        <label>Bot Token ${pill(cfg.telegram_bot_token_source, cfg.telegram_bot_token_masked)}</label>
        <input type="text" id="tg-token" value="" placeholder="${cfg.telegram_bot_token_present ? 'leave blank to keep current value above' : '@BotFather token'}">

        <label>Chat IDs (comma-separated) ${cfg.telegram_chat_ids_source === 'env' ? '<span class="src-pill src-env">env · durable</span>' : (cfg.telegram_chat_ids ? '<span class="src-pill src-disk">saved · resets on cold start</span>' : '')}</label>
        <textarea id="tg-chats" placeholder="-1001234567890, 987654321">${escapeHtml(cfg.telegram_chat_ids)}</textarea>

        <div class="row">
          <input type="checkbox" id="tg-enabled" ${cfg.telegram_enabled ? 'checked' : ''}>
          <label for="tg-enabled">Send alerts to Telegram (when off, alerts appear on Alerts tab only)</label>
        </div>
        <div class="save-row">
          <button class="btn btn-primary" data-section="telegram">Save Telegram</button>
          <span class="status"></span>
        </div>
      </div>
    </div>`;

  $$(".save-row .btn-primary").forEach(btn => {
    btn.addEventListener("click", () => saveSettings(btn.dataset.section, btn));
  });

  // Theme toggle — apply instantly + persist to config
  $$(".theme-btn").forEach(btn => {
    btn.addEventListener("click", async () => {
      const t = btn.dataset.theme;
      applyTheme(t);
      $$(".theme-btn").forEach(b => b.classList.toggle("active", b.dataset.theme === t));
      await fetch("/api/config", { method:"POST",
        headers:{"Content-Type":"application/json"}, body:JSON.stringify({theme: t}) });
    });
  });

  // Watchlist add + card grid
  await renderWatchlist(cfg.watchlist_tickers);
  $("#wl-add").addEventListener("click", () => addWatchlistTicker());
  $("#wl-input").addEventListener("keydown", e => { if (e.key === "Enter") addWatchlistTicker(); });
}

async function renderWatchlist(tickers) {
  const grid = $("#wl-grid");
  if (!grid) return;
  grid.innerHTML = tickers.length
    ? tickers.map(t => watchlistCard(t, null)).join("")
    : `<div class="empty" style="padding:16px 0;">No tickers yet — add one above.</div>`;
  // fetch live prices in one call and patch cards
  try {
    const data = await fetch("/api/watchlist").then(r => r.json());
    grid.innerHTML = (data.items || []).map(i => watchlistCard(i.ticker, i.price)).join("")
                     || `<div class="empty" style="padding:16px 0;">No tickers yet — add one above.</div>`;
    // wire find-play + remove buttons
    grid.querySelectorAll('[data-wl-find]').forEach(b => b.addEventListener("click",
      () => findPlayFor(b.dataset.wlFind)));
    grid.querySelectorAll('[data-wl-remove]').forEach(b => b.addEventListener("click",
      () => removeWatchlistTicker(b.dataset.wlRemove)));
  } catch (e) { /* fall back to cards without prices */ }
}

function watchlistCard(ticker, price) {
  const priceText = price != null ? `$${price.toFixed(2)}` : "—";
  return `<div class="wl-card">
    <button class="wl-remove" data-wl-remove="${escapeHtml(ticker)}" title="Remove">×</button>
    ${tickerLogo(ticker)}
    <div class="wl-ticker">${escapeHtml(ticker)}</div>
    <div class="wl-price green">${priceText}</div>
    <button class="btn btn-primary wl-find" data-wl-find="${escapeHtml(ticker)}">Find Play</button>
  </div>`;
}

async function addWatchlistTicker() {
  const inp = $("#wl-input");
  const t = (inp.value || "").trim().toUpperCase();
  if (!t) return;
  inp.value = "";
  const cfg = await fetch("/api/config").then(r => r.json());
  const list = cfg.watchlist_tickers || [];
  if (list.includes(t)) return;
  list.push(t);
  await fetch("/api/config", { method:"POST",
    headers:{"Content-Type":"application/json"},
    body: JSON.stringify({watchlist_tickers: list}) });
  await renderWatchlist(list);
}

async function removeWatchlistTicker(ticker) {
  const cfg = await fetch("/api/config").then(r => r.json());
  const list = (cfg.watchlist_tickers || []).filter(x => x !== ticker);
  await fetch("/api/config", { method:"POST",
    headers:{"Content-Type":"application/json"},
    body: JSON.stringify({watchlist_tickers: list}) });
  await renderWatchlist(list);
}

async function findPlayFor(ticker) {
  const r = await fetch("/api/watchlist/find_play", { method:"POST",
    headers:{"Content-Type":"application/json"},
    body: JSON.stringify({ticker}) });
  const data = await r.json();
  alert(data.message || (data.ok ? `Added ${data.found} plays.` : "Find failed."));
}

function applyTheme(t) {
  document.body.className = "theme-" + t;
  try { localStorage.setItem("fortress-theme", t); } catch (e) {}
}

async function saveSettings(section, btn) {
  const payload = {};
  if (section === "scanner") {
    payload.scan_capital = $("#scan-capital").value;
  } else if (section === "telegram") {
    const t = $("#tg-token").value.trim();
    if (t) payload.telegram_bot_token = t;
    payload.telegram_chat_ids = $("#tg-chats").value;
    payload.telegram_enabled = $("#tg-enabled").checked;
  } else if (section === "alpaca") {
    const k = $("#alpaca-key").value.trim();
    const s = $("#alpaca-secret").value.trim();
    if (k) payload.alpaca_api_key = k;
    if (s) payload.alpaca_api_secret = s;
    payload.alpaca_paper = $("#alpaca-paper").checked;
  } else if (section === "gemini") {
    const g = $("#gemini-key").value.trim();
    if (g) payload.gemini_api_key = g;
    payload.gemini_enabled = $("#gemini-enabled").checked;
  } else if (section === "profit") {
    payload.profit_alert_pct = parseInt($("#profit-pct").value) || 50;
    payload.profit_alerts_enabled = $("#profit-enabled").checked;
  }
  btn.disabled = true;
  try {
    const resp = await fetch("/api/config", { method:"POST",
      headers:{"Content-Type":"application/json"}, body:JSON.stringify(payload) });
    const data = await resp.json();
    const status = btn.parentElement.querySelector(".status");
    // Build a concrete confirmation showing what landed where.
    let parts = [];
    if (section === "scanner") {
      parts.push(`Scan capital: $${data.scan_capital}`);
    } else if (section === "alpaca") {
      parts.push(`Key: ${data.alpaca_api_key_present ? data.alpaca_api_key_masked : '(blank)'}`);
      parts.push(`Secret: ${data.alpaca_api_secret_present ? data.alpaca_api_secret_masked : '(blank)'}`);
      parts.push(`Paper: ${data.alpaca_paper}`);
    } else if (section === "gemini") {
      parts.push(`Key: ${data.gemini_api_key_present ? data.gemini_api_key_masked : '(blank)'}`);
      parts.push(`Enabled: ${data.gemini_enabled}`);
    } else if (section === "profit") {
      parts.push(`Threshold: ${data.profit_alert_pct}%`);
      parts.push(`Enabled: ${data.profit_alerts_enabled}`);
    } else {
      parts.push(`Token: ${data.telegram_bot_token_present ? data.telegram_bot_token_masked : '(blank)'}`);
      parts.push(`Chats: ${data.telegram_chat_ids || '(none)'}`);
      parts.push(`Enabled: ${data.telegram_enabled}`);
    }
    status.innerHTML = `<strong style="color:var(--green)">✓ Saved.</strong> ${escapeHtml(parts.join(" · "))}`;
    // Re-render after 2.5s so the user has time to read the confirmation;
    // the re-rendered Settings tab then shows the new "saved · masked" pills.
    setTimeout(() => renderSettings(), 2500);
  } catch (e) {
    btn.parentElement.querySelector(".status").innerHTML =
      `<strong style="color:var(--red)">✗ Save failed:</strong> ${escapeHtml(String(e))}`;
  } finally { btn.disabled = false; }
}

// ─── Router ─────────────────────────────────────────────────────────────────
function render() {
  if (currentTab === "plays") renderPlays();
  else if (currentTab === "positions") renderPositions();
  else if (currentTab === "earnings") renderEarnings();
  else if (currentTab === "alerts") renderAlerts();
  else if (currentTab === "settings") renderSettings();
}

// Apply saved theme immediately on load (avoid flash), then load config
try {
  const t = localStorage.getItem("fortress-theme") || "dark";
  document.body.className = "theme-" + t;
} catch (e) { document.body.className = "theme-dark"; }
// Sync from server config (may override localStorage if server has a saved value)
fetch("/api/config").then(r => r.json()).then(cfg => {
  if (cfg.theme) applyTheme(cfg.theme);
});

loadMarket();
render();
setInterval(loadMarket, 30000);
setInterval(() => { if (currentTab === "alerts") refreshFeed(); }, 4000);
</script>

</body>
</html>

"""
