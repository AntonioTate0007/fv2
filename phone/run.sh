#!/data/data/com.termux/files/usr/bin/bash
#
# Start Jarvis Phone and keep it alive.
#
#   ./run.sh                     bot + reminders, restarts if it crashes
#   ./run.sh chat                terminal REPL
#   ./run.sh doctor              health check
#   ./run.sh model <file.gguf>   start llama-server on :8080 in the background
#                                (the "local" brain), then the bot

cd "$(dirname "$0")"
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock

case "${1:-bot}" in
    chat|doctor|once)
        exec python -m jarvis_phone "$@"
        ;;
    model)
        GGUF="${2:?usage: ./run.sh model <file.gguf>}"
        if ! command -v llama-server >/dev/null 2>&1; then
            echo "llama-server not found: pkg install llama-cpp"; exit 1
        fi
        PORT="${LLAMA_PORT:-8080}"
        echo "Starting llama-server on :$PORT with $GGUF"
        nohup llama-server -m "$GGUF" --port "$PORT" -c 2048 -t "$(nproc)" \
            > "$HOME/llama.log" 2>&1 &
        sleep 3
        ;;
esac

# Supervisor loop: Termux kills processes on OOM/battery events; just come back.
while true; do
    python -m jarvis_phone bot
    code=$?
    [ "$code" -eq 2 ] && exit 2       # misconfigured (no token) — don't spin
    echo "jarvis exited ($code); restarting in 5s…"
    sleep 5
done
