#!/usr/bin/env bash
#
# Add Tailscale to a Pi that already has Fortress installed. Joins your
# tailnet so you can reach the Pi from any device with Tailscale
# (phone, laptop, another PC) without opening any router ports.
#
# Usage (paste on the Pi via SSH):
#   curl -sSL https://raw.githubusercontent.com/AntonioTate0007/fv2/main/server/add-tailscale.sh | bash
#
# What happens:
#   1. Installs Tailscale via their official installer
#   2. Runs `sudo tailscale up` — prints a URL you open on a device that's
#      already logged in to Tailscale (e.g. your PC) to authorize the Pi
#   3. Prints your Tailscale IP + the URL to use from anywhere
#
# Safe to re-run: idempotent, won't reset an already-connected node.

set -euo pipefail

log() { printf '\n\033[1;32m▸ %s\033[0m\n' "$*"; }

# Prime sudo up front — same pattern as setup-pi.sh
log "Priming sudo — enter the Pi's password once"
sudo -v
( while true; do sudo -n true; sleep 60; kill -0 "$$" || exit; done ) 2>/dev/null &
KEEPALIVE_PID=$!
trap 'kill "$KEEPALIVE_PID" 2>/dev/null || true' EXIT

# ── 1. Install Tailscale ─────────────────────────────────────────────────
if command -v tailscale >/dev/null 2>&1; then
    log "Tailscale already installed — skipping install"
else
    log "Installing Tailscale (Tailscale's official installer)"
    curl -fsSL https://tailscale.com/install.sh | sh
fi

# ── 2. Bring the node up ─────────────────────────────────────────────────
STATE="$(sudo tailscale status --json 2>/dev/null | grep -o '"BackendState":"[^"]*"' | head -n1 || true)"
if echo "$STATE" | grep -q 'Running'; then
    log "Tailscale is already up — leaving it alone"
else
    log "Bringing up Tailscale — a login URL will appear below"
    echo
    echo "  → Open the URL on a device that's already signed in to your"
    echo "    Tailscale account (e.g. this Pi's operator on their PC)."
    echo "    Approve the Pi. This tab will resume automatically."
    echo
    # --ssh lets you `tailscale ssh admin@<pi>` later without a password;
    # remove if you'd rather keep classic SSH-only.
    sudo tailscale up --ssh --accept-routes
fi

# ── 3. Print the Tailscale IP + hostname + connection info ───────────────
TS_IP="$(tailscale ip -4 2>/dev/null || echo '<not-connected>')"
TS_HOST="$(hostname)"
TS_MAGIC="$(sudo tailscale status --json 2>/dev/null \
            | grep -o '"MagicDNSSuffix":"[^"]*"' \
            | head -n1 | cut -d'"' -f4 || true)"

cat <<EOF

═════════════════════════════════════════════════════════════════════
  Tailscale is set up on this Pi.
═════════════════════════════════════════════════════════════════════

  Tailscale IP    : ${TS_IP}
  Hostname        : ${TS_HOST}
$( [ -n "$TS_MAGIC" ] && printf "  MagicDNS name   : %s.%s\n" "$TS_HOST" "$TS_MAGIC" )

  From any device that's signed in to Tailscale, hit:

      http://${TS_IP}:8000
$( [ -n "$TS_MAGIC" ] && printf "      http://%s.%s:8000\n" "$TS_HOST" "$TS_MAGIC" )

  These URLs work from anywhere (home wifi, cellular, hotel) as long
  as your device is logged in to your Tailscale account.

  Bonus: you can also SSH the Pi over Tailscale without a password:
      tailscale ssh admin@${TS_HOST}

  Cloudflare tunnel status
  ────────────────────────
  The trycloudflare.com tunnel is still running for public access.
  If you don't need public URLs anymore, stop and disable it:
      sudo systemctl stop fortress-tunnel
      sudo systemctl disable fortress-tunnel

═════════════════════════════════════════════════════════════════════
EOF
