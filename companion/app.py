"""
Fortress Companion v1.1 — cloud edition.

Web port of the original Pygame touchscreen UI. Single Flask service:

    /              dark/gold dashboard with status + slide-out log drawer
    /settings      config portal (Telegram bot token + chat ID)
    /save          POST: persist config to fortress_config.json
    /alert         POST: trigger a test Telegram alert
    /logs          GET:  JSON tail of recent log entries (drawer polls this)
    /healthz       GET:  liveness probe for Render

Config precedence: env vars (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID) act as
defaults; values saved through /settings override them at runtime and persist
to fortress_config.json. On ephemeral hosts (Render free tier) the JSON file
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
        "telegram_chat_id": os.environ.get("TELEGRAM_CHAT_ID", ""),
        "hardware_pref": "standard",
    }
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r") as f:
                cfg.update(json.load(f))
        except (OSError, json.JSONDecodeError) as e:
            log.warning("could not read %s: %s", CONFIG_FILE, e)
    return cfg


def _save_config(cfg: dict) -> None:
    with open(CONFIG_FILE, "w") as f:
        json.dump(cfg, f, indent=4)


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
        chat_id = app_config.get("telegram_chat_id", "")

    if not token or not chat_id:
        add_log("ERR: Telegram not configured — set token + chat ID in /settings", "error")
        return

    url = f"https://api.telegram.org/bot{token}/sendMessage"
    try:
        resp = requests.post(url, json={"chat_id": chat_id, "text": message}, timeout=10)
        if resp.ok:
            add_log(f"SENT: {message}", "success")
        else:
            add_log(f"ERR: Telegram {resp.status_code} — {resp.text[:120]}", "error")
    except requests.RequestException as e:
        add_log(f"ERR: {e}", "error")


# ── Flask app ───────────────────────────────────────────────────────────────

app = Flask(__name__)


DASHBOARD_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=800, initial-scale=1">
<title>Fortress Companion v1.1</title>
<style>
  :root {
    --bg: #121212;
    --panel: #1e1e1e;
    --gold: #ffd700;
    --green: #00ff80;
    --muted: #8a8a8a;
    --text: #e0e0e0;
    --border: #2a2a2a;
  }
  * { box-sizing: border-box; }
  html, body {
    margin: 0; padding: 0;
    background: var(--bg); color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Arial, sans-serif;
    height: 100vh; overflow: hidden;
  }
  .stage {
    position: relative;
    width: 100vw; height: 100vh;
    padding: 28px 32px;
  }
  h1 { margin: 0 0 6px 0; font-size: 26px; font-weight: 600; letter-spacing: 0.3px; }
  .status {
    font-size: 22px; color: var(--green); font-weight: 500;
    display: flex; align-items: center; gap: 10px;
  }
  .dot {
    width: 12px; height: 12px; border-radius: 50%;
    background: var(--green); box-shadow: 0 0 12px var(--green);
    animation: pulse 2s ease-in-out infinite;
  }
  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.35; }
  }
  .controls {
    position: absolute; bottom: 28px; left: 32px; right: 32px;
    display: flex; gap: 12px; align-items: center; justify-content: space-between;
  }
  .hint { color: var(--muted); font-size: 14px; }
  .btn {
    background: var(--gold); color: #121212; border: none;
    padding: 12px 22px; font-size: 15px; font-weight: 700;
    border-radius: 6px; cursor: pointer; letter-spacing: 0.2px;
    transition: transform 0.08s ease, box-shadow 0.15s ease;
  }
  .btn:hover { box-shadow: 0 0 18px rgba(255,215,0,0.35); }
  .btn:active { transform: translateY(1px); }
  .btn.ghost {
    background: transparent; color: var(--gold);
    border: 1px solid var(--gold);
  }
  .settings-link {
    position: absolute; top: 28px; right: 32px;
    color: var(--muted); text-decoration: none; font-size: 14px;
    border: 1px solid var(--border); padding: 6px 12px; border-radius: 4px;
  }
  .settings-link:hover { color: var(--gold); border-color: var(--gold); }

  /* Slide-out drawer */
  .drawer {
    position: absolute; top: 0; right: 0;
    width: 360px; height: 100%;
    background: var(--panel);
    border-left: 2px solid var(--gold);
    transform: translateX(100%);
    transition: transform 0.35s cubic-bezier(.2,.7,.2,1);
    display: flex; flex-direction: column;
    box-shadow: -8px 0 24px rgba(0,0,0,0.5);
  }
  .drawer.open { transform: translateX(0); }
  .drawer h2 {
    color: var(--gold); margin: 0;
    padding: 22px 22px 12px 22px;
    font-size: 18px; font-weight: 600;
    border-bottom: 1px solid var(--border);
  }
  .log-list {
    flex: 1; overflow-y: auto;
    padding: 12px 22px 22px 22px;
    font-family: ui-monospace, "SF Mono", Menlo, monospace;
    font-size: 13px;
  }
  .log-list .row {
    padding: 6px 0;
    border-bottom: 1px solid #262626;
    color: #c8c8c8;
    word-break: break-word;
  }
  .log-list .ts { color: var(--muted); margin-right: 8px; }
  .log-list .success { color: var(--green); }
  .log-list .error { color: #ff5c5c; }
  .log-list .info { color: #c8c8c8; }
  .log-list:empty::before {
    content: "No log entries yet."; color: var(--muted); font-style: italic;
  }
</style>
</head>
<body>
<div class="stage">
  <a class="settings-link" href="/settings">⚙ Settings</a>

  <h1>Fortress Companion v1.1</h1>
  <div class="status"><span class="dot"></span><span>System Status: SCANNING</span></div>

  <div class="controls">
    <span class="hint">Test the Telegram bridge or open the log drawer.</span>
    <div>
      <button class="btn ghost" id="toggleDrawer">Toggle Log Drawer</button>
      <button class="btn" id="testAlert">Trigger Test Alert</button>
    </div>
  </div>

  <aside class="drawer" id="drawer">
    <h2>Telegram Feed Logs</h2>
    <div class="log-list" id="logList"></div>
  </aside>
</div>

<script>
  const drawer = document.getElementById('drawer');
  const logList = document.getElementById('logList');
  const toggleBtn = document.getElementById('toggleDrawer');
  const alertBtn = document.getElementById('testAlert');

  toggleBtn.addEventListener('click', () => drawer.classList.toggle('open'));
  document.addEventListener('keydown', (e) => {
    if (e.key === 'd' || e.key === 'D') drawer.classList.toggle('open');
    if (e.key === 't' || e.key === 'T') triggerAlert();
  });

  alertBtn.addEventListener('click', triggerAlert);

  async function triggerAlert() {
    alertBtn.disabled = true;
    try {
      await fetch('/alert', { method: 'POST' });
      await refreshLogs();
      if (!drawer.classList.contains('open')) drawer.classList.add('open');
    } finally {
      setTimeout(() => { alertBtn.disabled = false; }, 800);
    }
  }

  async function refreshLogs() {
    try {
      const res = await fetch('/logs');
      const data = await res.json();
      logList.innerHTML = data.logs.slice(-50).map(row =>
        `<div class="row"><span class="ts">${row.ts}</span><span class="${row.level}">${escapeHtml(row.message)}</span></div>`
      ).reverse().join('');
    } catch (e) { /* ignore transient errors */ }
  }

  function escapeHtml(s) {
    return s.replace(/[&<>"']/g, c => ({
      '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'
    }[c]));
  }

  refreshLogs();
  setInterval(refreshLogs, 3000);
</script>
</body>
</html>
"""


SETTINGS_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Fortress Companion · Settings</title>
<style>
  body {
    background: #121212; color: #e0e0e0;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Arial, sans-serif;
    margin: 0; padding: 40px 32px; max-width: 640px;
  }
  h2 { color: #ffd700; margin: 0 0 8px 0; }
  .sub { color: #8a8a8a; margin-bottom: 28px; font-size: 14px; }
  label { display: block; margin: 18px 0 6px 0; font-size: 14px; color: #c8c8c8; }
  input[type=text] {
    width: 100%; padding: 10px 12px; font-size: 14px;
    background: #1e1e1e; color: #e0e0e0;
    border: 1px solid #2a2a2a; border-radius: 4px;
    font-family: ui-monospace, "SF Mono", Menlo, monospace;
  }
  input[type=text]:focus { outline: none; border-color: #ffd700; }
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
  <div class="sub">Telegram bridge configuration · v1.1</div>

  {% if saved %}<div class="flash">Settings saved to fortress_config.json.</div>{% endif %}

  <form action="/save" method="POST">
    <label for="bot_token">Telegram Bot Token</label>
    <input type="text" id="bot_token" name="bot_token" value="{{ config.telegram_bot_token }}" placeholder="123456:ABC-DEF...">

    <label for="chat_id">Telegram Chat ID</label>
    <input type="text" id="chat_id" name="chat_id" value="{{ config.telegram_chat_id }}" placeholder="-1001234567890">

    <div class="row">
      <button type="submit">Save Configuration</button>
      <a class="back" href="/">← Back to dashboard</a>
    </div>
  </form>

  <div class="note">
    On Render's free tier the container's filesystem is ephemeral — values saved here reset on every deploy.
    For durability, set <code>TELEGRAM_BOT_TOKEN</code> and <code>TELEGRAM_CHAT_ID</code> as environment variables in the Render dashboard.
  </div>
</body>
</html>
"""


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
        app_config["telegram_chat_id"] = request.form.get("chat_id", "").strip()
        try:
            _save_config(app_config)
        except OSError as e:
            add_log(f"ERR: could not persist config — {e}", "error")
    add_log("Configuration updated")
    return redirect(url_for("settings", saved=1))


@app.route("/alert", methods=["POST"])
def alert():
    add_log("Manual test alert triggered")
    threading.Thread(
        target=send_telegram_alert,
        args=("🚨 Fortress Test Action Triggered from Companion GUI",),
        daemon=True,
    ).start()
    return jsonify({"ok": True})


@app.route("/logs")
def logs_endpoint():
    with _logs_lock:
        return jsonify({"logs": list(logs)})


@app.route("/healthz")
def healthz():
    return jsonify({"ok": True})


if __name__ == "__main__":
    # Local dev only. Production uses gunicorn (see Dockerfile).
    port = int(os.environ.get("PORT", "5000"))
    app.run(host="0.0.0.0", port=port, debug=False)
