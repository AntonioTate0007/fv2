#!/usr/bin/env bash
#
# One-command Raspberry Pi installer for fortress-api.
#
# Usage (paste this on the Pi):
#   curl -sSL https://raw.githubusercontent.com/AntonioTate0007/fv2/main/server/setup-pi.sh | bash
#
# What it does:
#   1. Installs system deps (python, git, curl)
#   2. Clones/updates the fv2 repo into ~/fortress
#   3. Sets up a Python venv and installs requirements
#   4. Creates ~/fortress/server/.env with placeholder keys (you fill in)
#   5. Installs a systemd service so uvicorn auto-starts on boot
#   6. Installs cloudflared + a systemd service that publishes a public URL
#   7. Prints your local + public URLs
#
# Safe to re-run: everything is idempotent. Won't overwrite your .env or
# your systemd overrides once created.
#
# After it finishes:
#   - Local URL:   http://<pi-ip>:8000
#   - Public URL:  printed at the end (also findable via
#                  `journalctl -u fortress-tunnel | grep trycloudflare`)
#   - Edit env:    nano ~/fortress/server/.env  &&  sudo systemctl restart fortress
#   - View logs:   journalctl -u fortress -f
#                  journalctl -u fortress-tunnel -f
#   - Stop:        sudo systemctl stop fortress fortress-tunnel

set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/AntonioTate0007/fv2.git}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/fortress}"
SERVICE_USER="${SUDO_USER:-$USER}"
PORT="${PORT:-8000}"

log() { printf '\n\033[1;32m▸ %s\033[0m\n' "$*"; }
err() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; }

# ── 1. System deps ─────────────────────────────────────────────────────────
log "Installing system packages (may prompt for sudo)…"
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends \
    python3 python3-venv python3-pip git curl ca-certificates >/dev/null

# ── 2. Clone or update the repo ────────────────────────────────────────────
if [ -d "$INSTALL_DIR/.git" ]; then
    log "Repo already at $INSTALL_DIR — pulling latest main"
    git -C "$INSTALL_DIR" fetch origin
    git -C "$INSTALL_DIR" checkout main
    git -C "$INSTALL_DIR" pull --ff-only origin main
else
    log "Cloning $REPO_URL into $INSTALL_DIR"
    git clone "$REPO_URL" "$INSTALL_DIR"
fi

# ── 3. Python venv + requirements ──────────────────────────────────────────
cd "$INSTALL_DIR/server"
if [ ! -d .venv ]; then
    log "Creating Python venv"
    python3 -m venv .venv
fi
log "Installing Python requirements (this can take ~2 min on Pi)"
# shellcheck disable=SC1091
source .venv/bin/activate
pip install --upgrade -q pip
pip install --upgrade -q -r requirements.txt
deactivate

# ── 4. .env template (only if it doesn't exist) ────────────────────────────
if [ ! -f .env ]; then
    log "Creating .env template — you'll fill in your keys after setup"
    RANDOM_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
    cat > .env <<EOF
# Fortress backend config. Fill in your keys, then:
#   sudo systemctl restart fortress
FORTRESS_API_TOKEN=$RANDOM_TOKEN

# Alpaca — get from https://alpaca.markets/dashboard (Paper Trading section)
ALPACA_API_KEY=
ALPACA_API_SECRET=
ALPACA_PAPER=true

# Google Gemini — https://aistudio.google.com/app/apikey
GEMINI_API_KEY=

# Telegram bot — https://t.me/BotFather
TELEGRAM_BOT_TOKEN=
TELEGRAM_CHAT_ID=
EOF
    chmod 600 .env
fi

# ── 5. systemd service for fortress-api ────────────────────────────────────
log "Installing systemd unit: fortress.service"
sudo tee /etc/systemd/system/fortress.service >/dev/null <<EOF
[Unit]
Description=Fortress API (uvicorn)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
WorkingDirectory=$INSTALL_DIR/server
EnvironmentFile=$INSTALL_DIR/server/.env
ExecStart=$INSTALL_DIR/server/.venv/bin/uvicorn main:app --host 0.0.0.0 --port $PORT
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable fortress.service >/dev/null
sudo systemctl restart fortress.service

# ── 6. cloudflared (public URL, no port forwarding) ────────────────────────
if ! command -v cloudflared >/dev/null 2>&1; then
    log "Installing cloudflared (Cloudflare Tunnel)"
    ARCH="$(dpkg --print-architecture)"
    case "$ARCH" in
        arm64)  CFT_URL="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64.deb" ;;
        armhf)  CFT_URL="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-armhf.deb" ;;
        amd64)  CFT_URL="https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb" ;;
        *)      err "Unknown architecture $ARCH — install cloudflared manually then re-run"; exit 1 ;;
    esac
    TMPFILE="$(mktemp --suffix=.deb)"
    curl -fsSL -o "$TMPFILE" "$CFT_URL"
    sudo dpkg -i "$TMPFILE" >/dev/null
    rm -f "$TMPFILE"
fi

log "Installing systemd unit: fortress-tunnel.service"
sudo tee /etc/systemd/system/fortress-tunnel.service >/dev/null <<EOF
[Unit]
Description=Fortress Cloudflare Tunnel (public URL for fortress-api)
After=network-online.target fortress.service
Wants=network-online.target
Requires=fortress.service

[Service]
Type=simple
User=$SERVICE_USER
ExecStart=/usr/local/bin/cloudflared tunnel --url http://localhost:$PORT --no-autoupdate
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable fortress-tunnel.service >/dev/null
sudo systemctl restart fortress-tunnel.service

# ── 7. Wait for tunnel URL to appear in the logs ───────────────────────────
log "Waiting for Cloudflare Tunnel to publish a URL (up to 30s)…"
PUBLIC_URL=""
for _ in $(seq 1 15); do
    sleep 2
    PUBLIC_URL="$(sudo journalctl -u fortress-tunnel --since '2 minutes ago' \
                  -o cat 2>/dev/null | grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' \
                  | tail -n1 || true)"
    [ -n "$PUBLIC_URL" ] && break
done

PI_IP="$(hostname -I | awk '{print $1}')"

# ── 8. Summary ─────────────────────────────────────────────────────────────
cat <<EOF

═════════════════════════════════════════════════════════════════════
  Fortress is installed and running on this Pi.
═════════════════════════════════════════════════════════════════════

  Local URL      : http://$PI_IP:$PORT
  Public URL     : ${PUBLIC_URL:-"(pending — check: sudo journalctl -u fortress-tunnel -n 30)"}
  Config file    : $INSTALL_DIR/server/.env
  API token      : (auto-generated in .env, cat it to view)

  Next steps
  ─────────────
  1. Edit .env with your Alpaca / Gemini / Telegram keys:
       nano $INSTALL_DIR/server/.env
  2. Restart to pick up the new keys:
       sudo systemctl restart fortress
  3. Verify:
       curl http://localhost:$PORT/healthz
       # should print: {"ok":true}

  Live logs
  ─────────
       journalctl -u fortress -f
       journalctl -u fortress-tunnel -f

  Auto-start
  ──────────
  Both services are enabled — Fortress will come back up automatically
  after a reboot.

═════════════════════════════════════════════════════════════════════
EOF
