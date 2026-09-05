#!/data/data/com.termux/files/usr/bin/bash
#
# One-command installer for Jarvis Phone inside Termux.
#
# On the phone, in Termux:
#   curl -sSL https://raw.githubusercontent.com/AntonioTate0007/fv2/main/phone/setup-termux.sh | bash
#
# What it does:
#   1. Installs python, git, termux-api (the CLI half of Termux:API)
#   2. Clones/updates the fv2 repo into ~/fortress and installs phone/requirements.txt
#   3. Scaffolds phone/.env for you to fill in
#   4. Optionally installs llama.cpp so a small local model can be the brain
#   5. Installs a Termux:Boot script so Jarvis starts when the phone boots
#   6. Drops Termux:Widget shortcuts (~/.shortcuts/Jarvis) for tap-to-talk from the
#      home screen — the same scripts back the sticky notification's buttons
#
# Prerequisites (install from F-Droid, NOT Play Store — the Play build is stale):
#   • Termux            https://f-droid.org/packages/com.termux/
#   • Termux:API        https://f-droid.org/packages/com.termux.api/
#   • Termux:Widget     https://f-droid.org/packages/com.termux.widget/  (home-screen Jarvis button)
#   • Termux:Boot       https://f-droid.org/packages/com.termux.boot/    (optional, autostart)
#
# Safe to re-run: everything is idempotent and won't overwrite an existing .env.

set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/AntonioTate0007/fv2.git}"
BRANCH="${BRANCH:-main}"
INSTALL_DIR="${INSTALL_DIR:-$HOME/fortress}"
PHONE_DIR="$INSTALL_DIR/phone"

log() { printf '\n\033[1;32m▸ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m! %s\033[0m\n' "$*"; }

if [ -z "${PREFIX:-}" ] || [ ! -d /data/data/com.termux ]; then
    warn "This doesn't look like Termux. Run this on the phone."
    exit 1
fi

# ── 1. Packages ───────────────────────────────────────────────────────────────
log "Installing packages (python, git, termux-api)…"
pkg update -y -q >/dev/null 2>&1 || true
pkg install -y -q python git termux-api termux-am >/dev/null

# Let the floating-ring app (phone/overlay) start Jarvis via Termux's RUN_COMMAND
# intent. Harmless if you never install the overlay.
mkdir -p "$HOME/.termux"
if ! grep -qE '^\s*allow-external-apps\s*=\s*true' "$HOME/.termux/termux.properties" 2>/dev/null; then
    echo "allow-external-apps = true" >> "$HOME/.termux/termux.properties"
    termux-reload-settings 2>/dev/null || true
fi

# Termux needs explicit permission to reach the phone's storage for photos etc.
if [ ! -d "$HOME/storage" ]; then
    log "Requesting storage access (tap Allow on the phone)…"
    termux-setup-storage || true
fi

# ── 2. Repo + deps ────────────────────────────────────────────────────────────
if [ -d "$INSTALL_DIR/.git" ]; then
    log "Updating $INSTALL_DIR…"
    git -C "$INSTALL_DIR" fetch -q --depth 1 origin "$BRANCH" && \
        git -C "$INSTALL_DIR" checkout -q -B "$BRANCH" FETCH_HEAD || \
        warn "git update failed — continuing with what's there"
else
    log "Cloning $BRANCH into $INSTALL_DIR…"
    git clone -q --depth 1 -b "$BRANCH" "$REPO_URL" "$INSTALL_DIR"
fi

log "Installing Python requirements…"
pip install -q --upgrade pip >/dev/null 2>&1 || true
pip install -q -r "$PHONE_DIR/requirements.txt"

# ── 3. .env ───────────────────────────────────────────────────────────────────
if [ ! -f "$PHONE_DIR/.env" ]; then
    cp "$PHONE_DIR/.env.example" "$PHONE_DIR/.env"
    log "Created $PHONE_DIR/.env — add your Telegram token: nano $PHONE_DIR/.env"
fi

# ── 4. Optional local model ───────────────────────────────────────────────────
if ! command -v llama-server >/dev/null 2>&1; then
    if [ -t 0 ]; then
        read -r -p "Install llama.cpp for a local on-device brain? [y/N] " yn || yn=n
    else
        yn="${INSTALL_LLAMA:-n}"
    fi
    if [[ "$yn" =~ ^[Yy] ]]; then
        log "Installing llama.cpp…"
        pkg install -y -q llama-cpp >/dev/null && \
            echo "   Drop a small GGUF into ~/models and start with: ./run.sh model ~/models/<file>.gguf"
    fi
fi

# ── 5. Autostart via Termux:Boot ──────────────────────────────────────────────
BOOT_DIR="$HOME/.termux/boot"
mkdir -p "$BOOT_DIR"
cat > "$BOOT_DIR/jarvis.sh" <<BOOT
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
cd "$PHONE_DIR" && nohup ./run.sh > "$HOME/jarvis.log" 2>&1 &
BOOT
chmod +x "$BOOT_DIR/jarvis.sh" "$PHONE_DIR/run.sh"

# ── 6. Home-screen shortcuts (Termux:Widget) ──────────────────────────────────
SHORTCUTS="$HOME/.shortcuts"
mkdir -p "$SHORTCUTS/tasks"
cat > "$SHORTCUTS/Jarvis" <<SC
#!/data/data/com.termux/files/usr/bin/bash
# Tap → Android speech recogniser → Jarvis acts → speaks the reply.
cd "$PHONE_DIR" && exec python -m jarvis_phone listen
SC
cat > "$SHORTCUTS/Jarvis-Type" <<SC
#!/data/data/com.termux/files/usr/bin/bash
cd "$PHONE_DIR" && exec python -m jarvis_phone type
SC
cat > "$SHORTCUTS/Jarvis-Conversation" <<SC
#!/data/data/com.termux/files/usr/bin/bash
# Keeps listening after each answer until you say "bye".
cd "$PHONE_DIR" && exec python -m jarvis_phone listen --conversation
SC
cat > "$SHORTCUTS/Jarvis-Start" <<SC
#!/data/data/com.termux/files/usr/bin/bash
# Start (or restart) the bot daemon in the background.
pkill -f "phone/run.sh" 2>/dev/null || true      # supervisor loop first…
pkill -f "jarvis_phone bot" 2>/dev/null || true   # …then the bot it would restart
termux-wake-lock 2>/dev/null || true
cd "$PHONE_DIR" && nohup ./run.sh > "\$HOME/jarvis.log" 2>&1 &
termux-toast "Jarvis started" 2>/dev/null || true
SC
cat > "$SHORTCUTS/Jarvis-Doctor" <<SC
#!/data/data/com.termux/files/usr/bin/bash
# Health check → notification (readable without opening a terminal).
cd "$PHONE_DIR" && python -m jarvis_phone doctor 2>&1 | tee "\$HOME/jarvis-doctor.txt" | \
    termux-notification --id jarvis-doctor --title "Jarvis doctor" --content "\$(head -c 800 "\$HOME/jarvis-doctor.txt")" 2>/dev/null || true
SC
chmod +x "$SHORTCUTS"/Jarvis* "$PHONE_DIR/configure.sh" 2>/dev/null || true

# Tell the Jarvis Overlay app (if installed) that bootstrap finished, so its
# setup wizard can move on. Harmless when the app isn't there.
if command -v am >/dev/null 2>&1; then
    am broadcast -n com.fortress.jarvis.overlay/.StateReceiver \
        -a com.fortress.jarvis.overlay.SETUP_DONE >/dev/null 2>&1 || true
fi

# ── Done ──────────────────────────────────────────────────────────────────────
cat <<DONE

$(printf '\033[1;32m✓ Jarvis Phone is installed.\033[0m')

  Next:
    1. nano $PHONE_DIR/.env            # add TELEGRAM_BOT_TOKEN (from @BotFather)
    2. cd $PHONE_DIR && python -m jarvis_phone doctor
    3. ./run.sh                        # start the bot (Ctrl-C to stop)
       Message your bot /start — it tells you your chat id → TELEGRAM_CHAT_ID

  Try it in the terminal first, no Telegram needed:
       python -m jarvis_phone chat

  Floating Jarvis ring over every app: install the Jarvis Overlay APK
  (built by the "overlay-apk" GitHub Action, or `cd phone/overlay && ./gradlew
  assembleDebug` in Android Studio), grant its two permissions, tap Start.
  The ring pulses while Jarvis talks and goes translucent when idle; tap it to talk.

  Tap-to-talk from the home screen: long-press the launcher → Widgets →
  Termux:Widget → drop the "Jarvis" shortcut. Tapping it opens the speech
  recogniser; Jarvis answers out loud. While the bot runs, a sticky
  notification also offers Talk / Type buttons.

  Keep it alive: Android Settings → Apps → Termux → Battery → Unrestricted.
  Autostart on boot needs the Termux:Boot app opened once after install.
DONE
