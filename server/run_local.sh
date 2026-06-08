#!/usr/bin/env bash
#
# run_local.sh — launch the Fortress backend + Jarvis Telegram bot on your own
# machine, where the network can actually reach Telegram and your broker.
#
#   1. Create a bot with @BotFather in Telegram → copy the token.
#   2. ./run_local.sh         (first run scaffolds .env, then stop and fill it in)
#   3. ./run_local.sh         (boots the API + dashboard + Telegram polling)
#
# The bot runs in long-polling mode (no public URL needed). Open the dashboard at
# http://127.0.0.1:8000/dashboard and DM your bot /start.
#
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
VENV=".venv"
PORT="${PORT:-8000}"

# ── .env scaffolding ──────────────────────────────────────────────────────────
if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "✗ No .env found — created one from .env.example."
  echo
  echo "  Open server/.env and set at minimum:"
  echo "    TELEGRAM_BOT_TOKEN=<token from @BotFather>   (to use the bot)"
  echo "    GEMINI_API_KEY=<aistudio.google.com key>     (for chat + Vibe agent)"
  echo "    ALPACA_API_KEY / ALPACA_API_SECRET           (optional: real paper acct)"
  echo
  echo "  Leave PUBLIC_BASE_URL blank for local (Jarvis will long-poll Telegram)."
  echo "  Then re-run ./run_local.sh"
  exit 0
fi

# ── venv + deps (install only when requirements change) ─────────────────────────
if [[ ! -d "$VENV" ]]; then
  echo "→ creating virtualenv ($VENV)…"
  "$PYTHON" -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"

if [[ ! -f "$VENV/.deps-installed" || requirements.txt -nt "$VENV/.deps-installed" ]]; then
  echo "→ installing requirements…"
  pip install --quiet --upgrade pip
  pip install --quiet -r requirements.txt
  touch "$VENV/.deps-installed"
fi

# ── load .env ───────────────────────────────────────────────────────────────────
set -a
# shellcheck disable=SC1091
source .env
set +a
export PUBLIC_BASE_URL=""   # force polling mode for local runs

# ── preflight ─────────────────────────────────────────────────────────────────
if [[ -z "${TELEGRAM_BOT_TOKEN:-}" ]]; then
  echo "⚠  TELEGRAM_BOT_TOKEN is empty — the API/dashboard will run, but the bot won't."
else
  echo "→ checking Telegram token…"
  if me=$(curl -sS -m 15 "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getMe" 2>/dev/null) \
     && echo "$me" | grep -q '"ok":true'; then
    uname=$(echo "$me" | sed -n 's/.*"username":"\([^"]*\)".*/\1/p')
    echo "  ✓ connected as @${uname} — DM it /start once the server is up."
  else
    echo "  ✗ couldn't verify the token (check it, and that this machine can reach"
    echo "    api.telegram.org). The server will still start."
  fi
fi
command -v ffmpeg >/dev/null 2>&1 \
  && echo "→ ffmpeg found — Jarvis will send true voice notes." \
  || echo "→ ffmpeg not found — Jarvis will send audio files instead (still spoken)."

echo
echo "→ starting Fortress on http://127.0.0.1:${PORT}"
echo "   dashboard: http://127.0.0.1:${PORT}/dashboard"
echo
exec uvicorn main:app --host 127.0.0.1 --port "${PORT}" --reload
