"""
Fortress Companion v1.1 — cloud edition.

Web port of the original Pygame touchscreen UI. Single Flask service:

    /              dark/gold dashboard with always-visible alert feed
    /settings      config portal (Telegram bot token, chat IDs, on/off)
    /save          POST: persist config to fortress_config.json
    /alert         POST: trigger a test alert (always logged on-screen,
                         relayed to Telegram only if enabled + configured)
    /logs          GET:  JSON tail of recent log entries (feed polls this)
    /healthz       GET:  liveness probe for Render

Config precedence: env vars (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID, the
latter may be comma-separated for multiple recipients) act as defaults;
values saved through /settings override them at runtime and persist to
fortress_config.json. On ephemeral hosts (Render free tier) the JSON file
resets on every deploy — set the env vars in the dashboard for durability.
"""

from __future__ import annotations

import json
import logging
import os
import threading
from collections import deque
from datetime import datetime, timezone

import requests
from flask import Flask, jsonify, redirect, render_template_string, request, url_for

CONFIG_FILE = os.environ.get("FORTRESS_CONFIG_FILE", "fortress_config.json")
LOG_CAPACITY = 200

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("companion")


# ── Config ──────────────────────────────────────────────────────────────────

_config_lock = threading.Lock()


def _load_config() -> dict:
    cfg = {
        "telegram_bot_token": os.environ.get("TELEGRAM_BOT_TOKEN", ""),
        # comma-separated list of chat IDs; env var TELEGRAM_CHAT_ID can hold
        # one ID or several separated by commas
        "telegram_chat_ids": os.environ.get("TELEGRAM_CHAT_ID", ""),
        "telegram_enabled": True,
        "hardware_pref": "standard",
    }
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r") as f:
                stored = json.load(f)
                # back-compat: old configs used the singular `telegram_chat_id`
                if "telegram_chat_id" in stored and "telegram_chat_ids" not in stored:
                    stored["telegram_chat_ids"] = stored.pop("telegram_chat_id")
                cfg.update(stored)
        except (OSError, json.JSONDecodeError) as e:
            log.warning("could not read %s: %s", CONFIG_FILE, e)
    return cfg


def _save_config(cfg: dict) -> None:
    with open(CONFIG_FILE, "w") as f:
        json.dump(cfg, f, indent=4)


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
        add_log("ERR: Telegram not configured — set token + chat ID(s) in /settings", "error")
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


# ── Flask app ───────────────────────────────────────────────────────────────

app = Flask(__name__)


DASHBOARD_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Fortress Companion v1.1</title>
<style>
  :root {
    --bg: #121212;
    --panel: #1a1a1a;
    --panel-2: #1e1e1e;
    --gold: #ffd700;
    --green: #00ff80;
    --red: #ff5c5c;
    --muted: #8a8a8a;
    --text: #e0e0e0;
    --border: #2a2a2a;
  }
  * { box-sizing: border-box; }
  html, body {
    margin: 0; padding: 0;
    background: var(--bg); color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Arial, sans-serif;
    min-height: 100vh;
  }
  .page {
    max-width: 1100px; margin: 0 auto;
    padding: 24px 24px 40px 24px;
    display: flex; flex-direction: column; gap: 20px;
  }

  /* Header */
  header {
    display: flex; justify-content: space-between; align-items: center;
    flex-wrap: wrap; gap: 12px;
  }
  h1 { margin: 0; font-size: 24px; font-weight: 600; letter-spacing: 0.3px; }
  .settings-link {
    color: var(--muted); text-decoration: none; font-size: 14px;
    border: 1px solid var(--border); padding: 7px 14px; border-radius: 4px;
  }
  .settings-link:hover { color: var(--gold); border-color: var(--gold); }

  /* Status + actions card */
  .card {
    background: var(--panel); border: 1px solid var(--border);
    border-radius: 8px; padding: 20px;
  }
  .status-row {
    display: flex; justify-content: space-between; align-items: center;
    flex-wrap: wrap; gap: 16px;
  }
  .status {
    font-size: 20px; color: var(--green); font-weight: 500;
    display: flex; align-items: center; gap: 10px;
  }
  .dot {
    width: 12px; height: 12px; border-radius: 50%;
    background: var(--green); box-shadow: 0 0 12px var(--green);
    animation: pulse 2s ease-in-out infinite;
  }
  @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.35} }
  .meta { color: var(--muted); font-size: 13px; margin-top: 8px; }
  .meta .pill {
    display: inline-block; padding: 2px 8px; border-radius: 10px;
    background: #222; color: var(--muted); margin-right: 6px;
    border: 1px solid var(--border); font-size: 12px;
  }
  .meta .pill.on { color: var(--green); border-color: rgba(0,255,128,0.3); }
  .meta .pill.off { color: var(--muted); }

  .btn {
    background: var(--gold); color: #121212; border: none;
    padding: 12px 22px; font-size: 15px; font-weight: 700;
    border-radius: 6px; cursor: pointer; letter-spacing: 0.2px;
    transition: transform .08s ease, box-shadow .15s ease;
  }
  .btn:hover { box-shadow: 0 0 18px rgba(255,215,0,0.35); }
  .btn:active { transform: translateY(1px); }
  .btn:disabled { opacity: .6; cursor: wait; }

  /* Alert feed */
  .feed-head {
    display: flex; justify-content: space-between; align-items: baseline;
    margin-bottom: 12px;
  }
  .feed-head h2 {
    margin: 0; color: var(--gold); font-size: 17px; font-weight: 600;
  }
  .feed-head .count { color: var(--muted); font-size: 13px; }
  .feed {
    max-height: 60vh; overflow-y: auto;
    font-family: ui-monospace, "SF Mono", Menlo, monospace;
    font-size: 13.5px;
  }
  .feed .row {
    display: flex; gap: 12px; align-items: flex-start;
    padding: 10px 0; border-bottom: 1px solid #262626;
  }
  .feed .row:last-child { border-bottom: none; }
  .feed .ts { color: var(--muted); flex: 0 0 auto; }
  .feed .msg { word-break: break-word; flex: 1; }
  .feed .success { color: var(--green); }
  .feed .error { color: var(--red); }
  .feed .info { color: #c8c8c8; }
  .feed:empty::before {
    content: "No alerts yet."; color: var(--muted); font-style: italic;
    display: block; padding: 16px 0;
  }
  .new-flash { animation: flash .8s ease; }
  @keyframes flash {
    0% { background: rgba(255,215,0,.18); }
    100% { background: transparent; }
  }
</style>
</head>
<body>
<div class="page">
  <header>
    <h1>Fortress Companion v1.1</h1>
    <a class="settings-link" href="/settings">⚙ Settings</a>
  </header>

  <section class="card">
    <div class="status-row">
      <div>
        <div class="status"><span class="dot"></span><span>System Status: SCANNING</span></div>
        <div class="meta" id="meta"></div>
      </div>
      <button class="btn" id="testAlert">Trigger Test Alert</button>
    </div>
  </section>

  <section class="card">
    <div class="feed-head">
      <h2>Alerts</h2>
      <span class="count" id="feedCount"></span>
    </div>
    <div class="feed" id="feed"></div>
  </section>
</div>

<script>
  const feed = document.getElementById('feed');
  const feedCount = document.getElementById('feedCount');
  const metaEl = document.getElementById('meta');
  const alertBtn = document.getElementById('testAlert');
  let lastTopKey = '';

  alertBtn.addEventListener('click', triggerAlert);
  document.addEventListener('keydown', (e) => {
    const tag = (e.target && e.target.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA') return;
    if (e.key === 't' || e.key === 'T') triggerAlert();
  });

  async function triggerAlert() {
    alertBtn.disabled = true;
    try {
      await fetch('/alert', { method: 'POST' });
      await refreshLogs(true);
    } finally {
      setTimeout(() => { alertBtn.disabled = false; }, 600);
    }
  }

  async function refreshLogs(flashNew) {
    try {
      const res = await fetch('/logs');
      const data = await res.json();
      const rows = (data.logs || []).slice().reverse();
      feedCount.textContent = rows.length ? `${rows.length} entr${rows.length === 1 ? 'y' : 'ies'}` : '';
      feed.innerHTML = rows.map(r =>
        `<div class="row"><span class="ts">${r.ts}</span><span class="msg ${r.level}">${escapeHtml(r.message)}</span></div>`
      ).join('');
      if (flashNew && rows.length) {
        const top = feed.firstElementChild;
        const key = rows[0].ts + rows[0].message;
        if (top && key !== lastTopKey) top.classList.add('new-flash');
        lastTopKey = key;
      } else if (rows.length) {
        lastTopKey = rows[0].ts + rows[0].message;
      }
      renderMeta(data.config || {});
    } catch (e) { /* ignore transient errors */ }
  }

  function renderMeta(cfg) {
    const parts = [];
    parts.push(`<span class="pill ${cfg.telegram_ready ? 'on' : 'off'}">Telegram: ${cfg.telegram_ready ? 'on' : 'off'}</span>`);
    if (cfg.telegram_chat_count > 0) {
      parts.push(`<span class="pill">${cfg.telegram_chat_count} recipient${cfg.telegram_chat_count === 1 ? '' : 's'}</span>`);
    }
    metaEl.innerHTML = parts.join(' ');
  }

  function escapeHtml(s) {
    return s.replace(/[&<>"']/g, c => ({
      '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'
    }[c]));
  }

  refreshLogs(false);
  setInterval(() => refreshLogs(false), 3000);
</script>
</body>
</html>
"""


SETTINGS_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Fortress Companion · Settings</title>
<style>
  body {
    background: #121212; color: #e0e0e0;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Arial, sans-serif;
    margin: 0; padding: 40px 24px; max-width: 720px; margin-left:auto; margin-right:auto;
  }
  h2 { color: #ffd700; margin: 0 0 6px 0; }
  .sub { color: #8a8a8a; margin-bottom: 28px; font-size: 14px; }
  label { display: block; margin: 20px 0 6px 0; font-size: 14px; color: #c8c8c8; }
  .hint { color: #8a8a8a; font-size: 12px; margin-top: 4px; }
  input[type=text], textarea {
    width: 100%; padding: 10px 12px; font-size: 14px;
    background: #1e1e1e; color: #e0e0e0;
    border: 1px solid #2a2a2a; border-radius: 4px;
    font-family: ui-monospace, "SF Mono", Menlo, monospace;
    resize: vertical;
  }
  textarea { min-height: 70px; }
  input[type=text]:focus, textarea:focus { outline: none; border-color: #ffd700; }

  .toggle {
    display: flex; align-items: center; gap: 12px;
    padding: 14px 16px; background: #1a1a1a;
    border: 1px solid #2a2a2a; border-radius: 6px;
    margin-top: 12px;
  }
  .toggle input { width: 18px; height: 18px; accent-color: #ffd700; cursor: pointer; }
  .toggle label { margin: 0; cursor: pointer; flex: 1; color: #e0e0e0; font-size: 14px; }
  .toggle .desc { color: #8a8a8a; font-size: 12px; display: block; margin-top: 3px; }

  .row { display: flex; gap: 14px; margin-top: 28px; align-items: center; }
  button {
    background: #ffd700; color: #121212; border: none;
    padding: 11px 22px; font-size: 14px; font-weight: 700;
    border-radius: 5px; cursor: pointer;
  }
  a.back { color: #8a8a8a; text-decoration: none; font-size: 14px; }
  a.back:hover { color: #ffd700; }
  .flash {
    background: #1e2a1e; color: #00ff80; padding: 10px 14px;
    border-left: 3px solid #00ff80; margin-bottom: 20px; font-size: 14px;
  }
  .note {
    margin-top: 32px; padding: 14px; background: #1a1a1a;
    border-left: 3px solid #ffd700; font-size: 13px; color: #b0b0b0;
  }
  code { color: #ffd700; }
</style>
</head>
<body>
  <h2>Fortress Companion Settings</h2>
  <div class="sub">Configure the on-screen alert feed and Telegram bridge · v1.1</div>

  {% if saved %}<div class="flash">Settings saved.</div>{% endif %}

  <form action="/save" method="POST">
    <label for="bot_token">Telegram Bot Token</label>
    <input type="text" id="bot_token" name="bot_token" value="{{ config.telegram_bot_token }}" placeholder="123456:ABC-DEF...">
    <div class="hint">Get this from @BotFather on Telegram.</div>

    <label for="chat_ids">Telegram Chat IDs</label>
    <textarea id="chat_ids" name="chat_ids" placeholder="-1001234567890, 987654321">{{ config.telegram_chat_ids }}</textarea>
    <div class="hint">One or more chat IDs, separated by commas. Each alert goes to every recipient. DM @userinfobot on Telegram to find your chat ID.</div>

    <div class="toggle">
      <input type="checkbox" id="telegram_enabled" name="telegram_enabled" {% if config.telegram_enabled %}checked{% endif %}>
      <label for="telegram_enabled">
        Send alerts to Telegram
        <span class="desc">When off, alerts only appear on the on-screen feed. Useful for quiet hours or web-only viewing.</span>
      </label>
    </div>

    <div class="row">
      <button type="submit">Save Settings</button>
      <a class="back" href="/">← Back to dashboard</a>
    </div>
  </form>

  <div class="note">
    On Render's free tier the container's filesystem is ephemeral — values saved here reset on every deploy.
    For durability set <code>TELEGRAM_BOT_TOKEN</code> and <code>TELEGRAM_CHAT_ID</code> (comma-separated is fine) as environment variables in the Render dashboard.
  </div>
</body>
</html>
"""


def _status_summary() -> dict:
    with _config_lock:
        token = app_config.get("telegram_bot_token", "")
        ids = _parse_chat_ids(app_config.get("telegram_chat_ids", ""))
        enabled = bool(app_config.get("telegram_enabled", True))
    return {
        "telegram_chat_count": len(ids),
        "telegram_ready": bool(token and ids and enabled),
        "telegram_enabled": enabled,
    }


@app.route("/")
def index():
    return render_template_string(DASHBOARD_HTML)


@app.route("/settings")
def settings():
    saved = request.args.get("saved") == "1"
    with _config_lock:
        cfg = dict(app_config)
    return render_template_string(SETTINGS_HTML, config=cfg, saved=saved)


@app.route("/save", methods=["POST"])
def save():
    with _config_lock:
        app_config["telegram_bot_token"] = request.form.get("bot_token", "").strip()
        app_config["telegram_chat_ids"] = request.form.get("chat_ids", "").strip()
        app_config["telegram_enabled"] = request.form.get("telegram_enabled") == "on"
        app_config.pop("telegram_chat_id", None)  # remove legacy key on save
        try:
            _save_config(app_config)
        except OSError as e:
            add_log(f"ERR: could not persist config — {e}", "error")
    add_log("Configuration updated")
    return redirect(url_for("settings", saved=1))


@app.route("/alert", methods=["POST"])
def alert():
    add_log("🚨 Manual test alert triggered")
    threading.Thread(
        target=send_telegram_alert,
        args=("🚨 Fortress Test Action Triggered from Companion GUI",),
        daemon=True,
    ).start()
    return jsonify({"ok": True})


@app.route("/logs")
def logs_endpoint():
    with _logs_lock:
        rows = list(logs)
    return jsonify({"logs": rows, "config": _status_summary()})


@app.route("/healthz")
def healthz():
    return jsonify({"ok": True})


if __name__ == "__main__":
    # Local dev only. Production uses gunicorn (see Dockerfile).
    port = int(os.environ.get("PORT", "5000"))
    app.run(host="0.0.0.0", port=port, debug=False)
