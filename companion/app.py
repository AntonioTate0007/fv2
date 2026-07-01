"""
Fortress Companion v1.1 — cloud edition.

Web companion app that consumes the existing fortress-api backend and
renders a tabbed dashboard: Plays / Positions / Earnings / Alerts /
Settings. Long-press on a play opens a Manual Order Instructions modal
with computed legs + P&L chart.

Public routes:
    /                 single-page app (SPA)
    /healthz          liveness probe

Backend proxies (gated by FORTRESS_API_TOKEN, never exposed to browser):
    /api/scan         → fortress-api GET /v1/radar/scan?capital=N
    /api/positions    → fortress-api GET /v1/armory/positions
    /api/market       computed: VIX/SPY/premium-sell-rating (stubbed)

Companion-local:
    /api/config       GET/POST settings as JSON
    /api/alert        POST test Telegram alert
    /api/logs         GET tail of in-memory alerts log

Required env vars (none are strictly required — missing values trigger
demo data so the UI is always functional):
    FORTRESS_API_URL          base URL of fortress-api (e.g. https://...onrender.com)
    FORTRESS_API_TOKEN        bearer token for /v1/* routes
    TELEGRAM_BOT_TOKEN        @BotFather token; falls back to /settings UI
    TELEGRAM_CHAT_ID          one or more chat IDs, comma-separated

Config saved through /api/config persists to fortress_config.json. On
Render's free tier that file resets on every deploy; env vars are
durable.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from collections import deque
from datetime import datetime, timezone
from typing import Any

import requests
from flask import Flask, abort, jsonify, request

CONFIG_FILE = os.environ.get("FORTRESS_CONFIG_FILE", "fortress_config.json")
LOG_CAPACITY = 200
BACKEND_TIMEOUT = 8.0
CACHE_TTL = 15.0  # seconds — scan/positions are expensive; cache aggressively

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("companion")


# ── Config ──────────────────────────────────────────────────────────────────

_config_lock = threading.Lock()


def _load_config() -> dict:
    cfg = {
        "telegram_bot_token": os.environ.get("TELEGRAM_BOT_TOKEN", ""),
        "telegram_chat_ids": os.environ.get("TELEGRAM_CHAT_ID", ""),
        "telegram_enabled": True,
        "fortress_api_url": os.environ.get("FORTRESS_API_URL", ""),
        "fortress_api_token": os.environ.get("FORTRESS_API_TOKEN", ""),
        "scan_capital": 5000,
        "alpaca_api_key": os.environ.get("ALPACA_API_KEY", ""),
        "alpaca_api_secret": os.environ.get("ALPACA_API_SECRET", ""),
        "alpaca_paper": (os.environ.get("ALPACA_PAPER", "true").lower() != "false"),
        "gemini_api_key": os.environ.get("GEMINI_API_KEY", ""),
        "gemini_enabled": True,
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


# Tracks which keys came from the environment at boot vs. which were saved
# later via /api/config. The Settings UI uses this so the user can tell at a
# glance whether a value will survive a container restart (env = durable,
# disk = ephemeral on Render free tier).
_env_keys_at_boot: set[str] = set()


def _record_env_keys() -> None:
    for env, cfg_key in (
        ("TELEGRAM_BOT_TOKEN", "telegram_bot_token"),
        ("TELEGRAM_CHAT_ID", "telegram_chat_ids"),
        ("FORTRESS_API_URL", "fortress_api_url"),
        ("FORTRESS_API_TOKEN", "fortress_api_token"),
        ("ALPACA_API_KEY", "alpaca_api_key"),
        ("ALPACA_API_SECRET", "alpaca_api_secret"),
        ("ALPACA_PAPER", "alpaca_paper"),
        ("GEMINI_API_KEY", "gemini_api_key"),
    ):
        if os.environ.get(env):
            _env_keys_at_boot.add(cfg_key)


_record_env_keys()


def _parse_chat_ids(raw: str) -> list[str]:
    return [p.strip() for p in (raw or "").split(",") if p.strip()]


app_config = _load_config()


# ── Logs (ring buffer, thread-safe) ─────────────────────────────────────────

_logs_lock = threading.Lock()
logs: deque[dict] = deque(maxlen=LOG_CAPACITY)


def add_log(message: str, level: str = "info") -> None:
    entry = {
        "ts": datetime.now(timezone.utc).strftime("%H:%M:%S"),
        "level": level,
        "message": message,
    }
    with _logs_lock:
        logs.append(entry)
    log.info("[%s] %s", level, message)


add_log("System initialized")


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


# ── Backend client (fortress-api) ───────────────────────────────────────────

_cache: dict[str, tuple[float, Any]] = {}
_cache_lock = threading.Lock()


def _backend_settings() -> tuple[str, str]:
    with _config_lock:
        return (
            app_config.get("fortress_api_url", "").rstrip("/"),
            app_config.get("fortress_api_token", ""),
        )


def _backend_headers() -> dict:
    """Auth + Alpaca + Gemini creds to forward to fortress-api on every /v1/* call."""
    with _config_lock:
        token = app_config.get("fortress_api_token", "")
        ak = app_config.get("alpaca_api_key", "")
        as_ = app_config.get("alpaca_api_secret", "")
        paper = bool(app_config.get("alpaca_paper", True))
        gk = app_config.get("gemini_api_key", "")
        gemini_on = bool(app_config.get("gemini_enabled", True))
    headers = {"Authorization": f"Bearer {token}"}
    if ak:
        headers["X-Alpaca-Key"] = ak
    if as_:
        headers["X-Alpaca-Secret"] = as_
    # Always pin paper/live explicitly so a typo in env vars can't accidentally
    # arm a live order. Defaults true → paper.
    headers["X-Alpaca-Paper"] = "true" if paper else "false"
    # Only forward Gemini key when analysis is enabled — respects the Settings
    # toggle without wiping the saved key.
    if gk and gemini_on:
        headers["X-Gemini-Key"] = gk
    return headers


def _cached_fetch(key: str, fetcher) -> tuple[Any, bool]:
    now = time.monotonic()
    with _cache_lock:
        cached = _cache.get(key)
        if cached and now - cached[0] < CACHE_TTL:
            return cached[1], True
    try:
        value = fetcher()
    except Exception:
        # On failure, fall back to last-known-good if we have one
        with _cache_lock:
            cached = _cache.get(key)
            if cached:
                return cached[1], True
        raise
    with _cache_lock:
        _cache[key] = (now, value)
    return value, False


def fetch_scan(capital: int) -> list[dict]:
    url, token = _backend_settings()
    if not url or not token:
        return _demo_scan()

    def _do():
        resp = requests.get(
            f"{url}/v1/radar/scan",
            params={"capital": capital},
            headers=_backend_headers(),
            timeout=BACKEND_TIMEOUT,
        )
        resp.raise_for_status()
        return resp.json()

    try:
        data, _ = _cached_fetch(f"scan:{capital}", _do)
        return data
    except requests.RequestException as e:
        add_log(f"ERR: backend scan — {e}", "error")
        return _demo_scan()


def fetch_positions() -> list[dict]:
    url, token = _backend_settings()
    if not url or not token:
        return _demo_positions()

    def _do():
        resp = requests.get(
            f"{url}/v1/armory/positions",
            headers=_backend_headers(),
            timeout=BACKEND_TIMEOUT,
        )
        resp.raise_for_status()
        return resp.json()

    try:
        data, _ = _cached_fetch("positions", _do)
        return data
    except requests.RequestException as e:
        add_log(f"ERR: backend positions — {e}", "error")
        return _demo_positions()


def fetch_earnings(tickers: list[str]) -> dict:
    """Earnings-blackout gate snapshot for the given tickers. Demo data when
    fortress-api isn't wired so the Earnings tab is never blank."""
    url, token = _backend_settings()
    cleaned = [t.strip().upper() for t in tickers if t and t.strip()]
    if not cleaned:
        return {"items": [], "blackoutDays": 7, "asOf": None, "demo": False}
    if not url or not token:
        return _demo_earnings(cleaned)

    def _do():
        resp = requests.get(
            f"{url}/v1/earnings",
            params={"tickers": ",".join(cleaned)},
            headers=_backend_headers(),
            timeout=BACKEND_TIMEOUT,
        )
        resp.raise_for_status()
        return resp.json()

    try:
        data, _ = _cached_fetch(f"earnings:{','.join(cleaned)}", _do)
        data.setdefault("demo", False)
        return data
    except requests.RequestException as e:
        add_log(f"ERR: backend earnings — {e}", "error")
        return _demo_earnings(cleaned)


def _demo_earnings(tickers: list[str]) -> dict:
    """Pretend-calendar: ETFs are always clear, every other ticker gets a
    fake next-earnings date 9 days out (puts them inside the 7-day blackout
    visualization so the gate UI is recognizable in demo mode)."""
    today = datetime.now(timezone.utc).date()
    items = []
    etfs = {"SPY", "QQQ", "IWM"}
    for t in tickers:
        is_etf = t in etfs
        if is_etf:
            items.append({"ticker": t, "nextEarningsDate": None, "daysUntil": None,
                          "withinBlackout": False, "isEtf": True})
        else:
            d = today.replace(day=1).replace(day=min(28, today.day))  # stable demo date
            from datetime import timedelta as _td
            nd = today + _td(days=9)
            items.append({"ticker": t, "nextEarningsDate": nd.isoformat(),
                          "daysUntil": 9, "withinBlackout": False, "isEtf": False})
    return {"items": items, "blackoutDays": 7,
            "asOf": datetime.now(timezone.utc).isoformat(), "demo": True}


def _demo_scan() -> list[dict]:
    """Sample plays so the UI works before fortress-api is wired. Each play is
    tuned to a different rating tier so the rating system is visible in demo
    mode without having to wait for live market conditions."""
    return [
        # 🔥 amazing: high POP, deep buffer, low delta
        {
            "id": "SPY-PCS-540",
            "ticker": "SPY",
            "strategy": "PUT_CREDIT_SPREAD",
            "shortStrike": 540.0,
            "longStrike": 535.0,
            "expiration": "2026-07-13",
            "dte": 13,
            "estimatedCreditPerContract": 0.45,
            "safetyBufferPct": 0.105,
            "underlyingPrice": 603.4,
            "probabilityOfProfit": 0.88,
            "shortDelta": 0.10,
            "ivRank": 0.52,
            "earningsClear": True,
            "demo": True,
        },
        # 👍 ok: solid but not exceptional
        {
            "id": "QQQ-PCS-470",
            "ticker": "QQQ",
            "strategy": "PUT_CREDIT_SPREAD",
            "shortStrike": 470.0,
            "longStrike": 465.0,
            "expiration": "2026-07-13",
            "dte": 13,
            "estimatedCreditPerContract": 0.55,
            "safetyBufferPct": 0.075,
            "underlyingPrice": 508.0,
            "probabilityOfProfit": 0.79,
            "shortDelta": 0.16,
            "ivRank": 0.46,
            "earningsClear": True,
            "demo": True,
        },
        # ⚠ risky: thin buffer, low POP
        {
            "id": "IWM-PCS-205",
            "ticker": "IWM",
            "strategy": "PUT_CREDIT_SPREAD",
            "shortStrike": 205.0,
            "longStrike": 200.0,
            "expiration": "2026-07-13",
            "dte": 13,
            "estimatedCreditPerContract": 0.85,
            "safetyBufferPct": 0.045,
            "underlyingPrice": 214.7,
            "probabilityOfProfit": 0.66,
            "shortDelta": 0.28,
            "ivRank": 0.51,
            "earningsClear": True,
            "demo": True,
        },
    ]


def _demo_positions() -> list[dict]:
    return [
        {
            "id": "GOOGL-2026-06-29-265",
            "ticker": "GOOGL",
            "strategyLabel": "$265 / $260 Put Spread",
            "shortStrike": 265.0,
            "underlyingPrice": 277.4,
            "entryPremium": 0.55,
            "currentPremium": 0.21,
            "expiration": "2026-07-06",
            "contracts": 1,
            "demo": True,
        }
    ]


def market_summary() -> dict:
    """VIX/SPY/Premium-Sell header. Backend doesn't expose these yet, so we
    derive a 'premium sell' rating from the plays we just scanned (avg IV
    rank) and stub VIX/SPY. When you later add a /v1/market endpoint, swap
    this for a real fetch."""
    plays = fetch_scan(app_config.get("scan_capital", 5000))
    iv_ranks = [p.get("ivRank") or 0 for p in plays if p.get("ivRank") is not None]
    avg_iv = sum(iv_ranks) / len(iv_ranks) if iv_ranks else 0.0
    if avg_iv >= 0.45:
        rating, sub = "OPTIMAL", "IV elevated"
    elif avg_iv >= 0.30:
        rating, sub = "FAIR", "moderate IV"
    else:
        rating, sub = "WAIT", "IV compressed"
    return {
        "vix": {"value": 14.8, "label": "LOW / CALM"},  # TODO: wire real VIX
        "spy": {"price": 737.23, "changePct": 0.24},      # TODO: wire real SPY
        "premiumSell": {"rating": rating, "sub": sub, "avgIvRank": round(avg_iv, 2)},
        "demo": not all(_backend_settings()),
    }


# ── Flask app ───────────────────────────────────────────────────────────────

app = Flask(__name__)


@app.route("/healthz")
def healthz():
    return jsonify({"ok": True})


@app.route("/api/config", methods=["GET", "POST"])
def api_config():
    if request.method == "POST":
        payload = request.get_json(silent=True) or {}
        with _config_lock:
            for key in (
                "telegram_bot_token", "telegram_chat_ids",
                "fortress_api_url", "fortress_api_token",
                "alpaca_api_key", "alpaca_api_secret",
                "gemini_api_key",
            ):
                if key in payload:
                    app_config[key] = str(payload[key]).strip()
            if "telegram_enabled" in payload:
                app_config["telegram_enabled"] = bool(payload["telegram_enabled"])
            if "alpaca_paper" in payload:
                app_config["alpaca_paper"] = bool(payload["alpaca_paper"])
            if "gemini_enabled" in payload:
                app_config["gemini_enabled"] = bool(payload["gemini_enabled"])
            if "scan_capital" in payload:
                try:
                    app_config["scan_capital"] = max(100, int(payload["scan_capital"]))
                except (TypeError, ValueError):
                    pass
            app_config.pop("telegram_chat_id", None)
            try:
                _save_config(app_config)
            except OSError as e:
                add_log(f"ERR: could not persist config — {e}", "error")
        add_log("Configuration updated")
        # bust caches that depend on backend creds
        with _cache_lock:
            _cache.clear()
    with _config_lock:
        cfg = dict(app_config)
    # never echo full secrets back to the browser — mask them
    def mask(v: str) -> str:
        return f"{v[:6]}…{v[-4:]}" if v and len(v) > 12 else ("set" if v else "")

    def source(key: str) -> str:
        """Where this value lives — guides the UX about persistence:
            env  → set as a host env var → durable across container restarts
            disk → saved through /api/config → lost on Render free spin-down
            none → not set
        """
        present = bool(cfg.get(key))
        if not present:
            return "none"
        return "env" if key in _env_keys_at_boot else "disk"

    return jsonify({
        "telegram_bot_token_masked": mask(cfg.get("telegram_bot_token", "")),
        "telegram_bot_token_present": bool(cfg.get("telegram_bot_token")),
        "telegram_bot_token_source": source("telegram_bot_token"),
        "telegram_chat_ids": cfg.get("telegram_chat_ids", ""),
        "telegram_chat_ids_source": source("telegram_chat_ids"),
        "telegram_enabled": bool(cfg.get("telegram_enabled", True)),
        "fortress_api_url": cfg.get("fortress_api_url", ""),
        "fortress_api_url_source": source("fortress_api_url"),
        "fortress_api_token_masked": mask(cfg.get("fortress_api_token", "")),
        "fortress_api_token_present": bool(cfg.get("fortress_api_token")),
        "fortress_api_token_source": source("fortress_api_token"),
        "scan_capital": cfg.get("scan_capital", 5000),
        "backend_connected": bool(cfg.get("fortress_api_url") and cfg.get("fortress_api_token")),
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
    })


@app.route("/api/scan")
def api_scan():
    try:
        capital = int(request.args.get("capital") or app_config.get("scan_capital", 5000))
    except ValueError:
        capital = 5000
    plays = fetch_scan(capital)
    return jsonify({"plays": plays, "capital": capital})


@app.route("/api/positions")
def api_positions():
    return jsonify({"positions": fetch_positions()})


@app.route("/api/market")
def api_market():
    return jsonify(market_summary())


@app.route("/api/analyze/play", methods=["POST"])
def api_analyze_play():
    """Proxy per-play AI analysis to fortress-api. Body is the play object as
    the browser already has it (from /api/scan). Falls back to a deterministic
    tooltip-style analysis when backend or Gemini isn't wired."""
    payload = request.get_json(silent=True) or {}
    url, token = _backend_settings()
    if not url or not token:
        return jsonify({"analysis": _local_analysis_fallback(payload), "source": "fallback"})

    try:
        resp = requests.post(
            f"{url}/v1/analyze/play",
            headers=_backend_headers(),
            json=payload,
            timeout=BACKEND_TIMEOUT * 2,  # Gemini can be slow — give it room
        )
        resp.raise_for_status()
        return jsonify(resp.json())
    except requests.RequestException as e:
        add_log(f"ERR: analyze/play — {e}", "error")
        return jsonify({"analysis": _local_analysis_fallback(payload), "source": "fallback"})


def _local_analysis_fallback(p: dict) -> str:
    buf = (p.get("safetyBufferPct") or 0) * 100
    pop = (p.get("probabilityOfProfit") or 0) * 100
    rating = (p.get("rating") or "").upper()
    ticker = p.get("ticker", "this ticker")
    short = p.get("shortStrike", 0)
    if rating == "AMAZING":
        return (f"{ticker} sits {buf:.1f}% above the ${short:.0f} short strike with "
                f"{pop:.0f}% POP — premium worth taking. Cut on a close under ${short:.0f}.")
    if rating == "OK":
        return (f"Standard setup: {buf:.1f}% cushion, {pop:.0f}% POP. Take if you have book "
                f"capacity; skip if correlated to existing positions.")
    return (f"Thin buffer ({buf:.1f}%) or low probability ({pop:.0f}%) — skip unless you "
            f"have a strong directional view on {ticker}.")


@app.route("/api/earnings")
def api_earnings():
    """Earnings-blackout gate. Caller hands us the ticker list (universe +
    held positions); we proxy the calendar lookup to fortress-api. The
    universe defaults to SPY/QQQ/IWM if no ?tickers= param is given."""
    raw = request.args.get("tickers", "")
    tickers = [t for t in raw.split(",") if t.strip()] or ["SPY", "QQQ", "IWM"]
    return jsonify(fetch_earnings(tickers))


@app.route("/api/alert", methods=["POST"])
def api_alert():
    payload = request.get_json(silent=True) or {}
    message = (payload.get("message") or "🚨 Fortress Test Action Triggered from Companion GUI").strip()
    add_log(f"🚨 {message}")
    threading.Thread(
        target=send_telegram_alert, args=(message,), daemon=True,
    ).start()
    return jsonify({"ok": True})


@app.route("/api/logs")
def api_logs():
    with _logs_lock:
        rows = list(logs)
    with _config_lock:
        token = app_config.get("telegram_bot_token", "")
        ids = _parse_chat_ids(app_config.get("telegram_chat_ids", ""))
        enabled = bool(app_config.get("telegram_enabled", True))
    return jsonify({
        "logs": rows,
        "telegram": {
            "ready": bool(token and ids and enabled),
            "enabled": enabled,
            "recipientCount": len(ids),
        },
    })


# Back-compat: the old single-page dashboard linked to /settings. Redirect
# anything that lands there to the new SPA (it has a Settings tab).
@app.route("/settings")
def settings_legacy():
    return INDEX_HTML, 200, {"Content-Type": "text/html; charset=utf-8"}


@app.route("/")
def index():
    return INDEX_HTML, 200, {"Content-Type": "text/html; charset=utf-8"}


# ── The single-page app ─────────────────────────────────────────────────────
# Inline for simplicity (no template dir, no static files). Dark/green
# aesthetic matching the screenshots; ~one big file is intentional.

INDEX_HTML = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Fortress Companion</title>
<style>
  :root {
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
    background: #fff; color: #000; display: flex; align-items: center;
    justify-content: center; font-weight: 800; font-size: 13px; flex-shrink: 0;
  }
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
    <div id="plays-list" class="empty">Loading…</div>`;
  $("#btn-scan").addEventListener("click", loadPlays);
  $("#btn-clear").addEventListener("click", () => { cachedPlays = []; renderPlayList([], false); });
  await loadPlays();
}

async function loadPlays() {
  const list = $("#plays-list");
  if (list) list.innerHTML = `<div class="empty">Scanning…</div>`;
  try {
    const r = await fetch("/api/scan");
    const data = await r.json();
    cachedPlays = data.plays || [];
    renderPlayList(cachedPlays, cachedPlays.some(p => p.demo));
  } catch (e) {
    list.innerHTML = `<div class="empty">Could not load plays.</div>`;
  }
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
        <div class="ticker-logo">${escapeHtml(p.ticker.slice(0,1))}</div>
        <div class="ticker-info">
          <div class="top">
            <h3>${escapeHtml(p.ticker)}</h3>
            <span class="rate-badge ${rating.cls}" title="${escapeHtml(rating.tip)}">${rating.emoji} ${rating.tag}</span>
          </div>
          <div class="meta">${ucase(strategyLabel(p.strategy))} • Exp: ${escapeHtml(p.expiration)} (${p.dte} DTE)</div>
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
  return `<div class="card">
    ${ernBadge}
    <div class="card-head">
      <div class="ticker-logo">${escapeHtml(p.ticker.slice(0,1))}</div>
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
      <div class="ticker-logo">${escapeHtml(item.ticker.slice(0,1))}</div>
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
        <h3>Fortress Backend ${cfg.backend_connected ? '<span class="src-pill src-ok">✓ connected</span>' : '<span class="src-pill src-none">not connected</span>'}</h3>
        <p class="desc">Connect to your existing fortress-api service so the Plays and Positions tabs show live data. Falls back to demo data when blank.</p>

        <label>Backend URL ${pill(cfg.fortress_api_url_source, cfg.fortress_api_url)}</label>
        <input type="text" id="api-url" value="${escapeHtml(cfg.fortress_api_url)}" placeholder="https://fortress-api.onrender.com">

        <label>Backend Token ${pill(cfg.fortress_api_token_source, cfg.fortress_api_token_masked)}</label>
        <input type="text" id="api-token" value="" placeholder="${cfg.fortress_api_token_present ? 'leave blank to keep current value above' : '(not set)'}">

        <label>Scan Capital</label>
        <input type="number" id="scan-capital" value="${cfg.scan_capital}" min="100" step="100">

        <div class="save-row">
          <button class="btn btn-primary" data-section="backend">Save Backend</button>
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
}

async function saveSettings(section, btn) {
  const payload = {};
  if (section === "backend") {
    payload.fortress_api_url = $("#api-url").value;
    payload.scan_capital = $("#scan-capital").value;
    const t = $("#api-token").value.trim();
    if (t) payload.fortress_api_token = t;
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
  }
  btn.disabled = true;
  try {
    const resp = await fetch("/api/config", { method:"POST",
      headers:{"Content-Type":"application/json"}, body:JSON.stringify(payload) });
    const data = await resp.json();
    const status = btn.parentElement.querySelector(".status");
    // Build a concrete confirmation showing what landed where.
    let parts = [];
    if (section === "backend") {
      parts.push(`URL: ${data.fortress_api_url || '(blank)'}`);
      parts.push(`Token: ${data.fortress_api_token_present ? data.fortress_api_token_masked : '(blank)'}`);
    } else if (section === "alpaca") {
      parts.push(`Key: ${data.alpaca_api_key_present ? data.alpaca_api_key_masked : '(blank)'}`);
      parts.push(`Secret: ${data.alpaca_api_secret_present ? data.alpaca_api_secret_masked : '(blank)'}`);
      parts.push(`Paper: ${data.alpaca_paper}`);
    } else if (section === "gemini") {
      parts.push(`Key: ${data.gemini_api_key_present ? data.gemini_api_key_masked : '(blank)'}`);
      parts.push(`Enabled: ${data.gemini_enabled}`);
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

loadMarket();
render();
setInterval(loadMarket, 30000);
setInterval(() => { if (currentTab === "alerts") refreshFeed(); }, 4000);
</script>

</body>
</html>
"""


if __name__ == "__main__":
    # Local dev only. Production uses gunicorn (see Dockerfile).
    port = int(os.environ.get("PORT", "5000"))
    app.run(host="0.0.0.0", port=port, debug=False)
