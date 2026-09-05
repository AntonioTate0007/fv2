#!/data/data/com.termux/files/usr/bin/bash
#
# Write settings into phone/.env without opening an editor.
#
#   ./configure.sh TELEGRAM_BOT_TOKEN=123:abc TELEGRAM_CHAT_ID=42 GEMINI_API_KEY=
#
# Each KEY=VALUE replaces the existing line (or appends). An empty value clears
# the key. The Jarvis Overlay app calls this through Termux's RUN_COMMAND intent.
# Prints the keys it changed, never the values.

set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] || cp .env.example .env

changed=()
for kv in "$@"; do
    key="${kv%%=*}"
    value="${kv#*=}"
    if [[ ! "$key" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
        echo "skip: bad key '$key'" >&2
        continue
    fi
    # escape sed replacement specials in the value
    esc=$(printf '%s' "$value" | sed -e 's/[\/&|]/\\&/g')
    if grep -qE "^${key}=" .env; then
        sed -i -E "s|^${key}=.*|${key}=${esc}|" .env
    else
        printf '%s=%s\n' "$key" "$value" >> .env
    fi
    changed+=("$key")
done
chmod 600 .env
echo "updated: ${changed[*]:-nothing}"
command -v termux-toast >/dev/null 2>&1 && termux-toast "Jarvis config saved" || true
