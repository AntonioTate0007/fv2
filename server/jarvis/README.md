# Jarvis — Telegram trading assistant

Your personal, voice-capable trading assistant (inspired by
[OpenJarvis](https://github.com/open-jarvis/OpenJarvis)'s scheduled, TTS-narrated
digest agents). Jarvis sits on top of the agent swarm and talks to you over
**Telegram** with both **text** and **voice** — so the swarm's decisions reach your
phone as a spoken briefing, and you can drive it back with chat commands.

## Setup

1. Create a bot with [@BotFather](https://t.me/BotFather) → copy the token.
2. Set `TELEGRAM_BOT_TOKEN`. Message your bot `/start` and it replies with your chat
   id; put that in `TELEGRAM_CHAT_ID` (this is the **only** chat allowed to issue
   trading commands).
3. Delivery mode:
   - **Webhook** (recommended for the deployed server): set `PUBLIC_BASE_URL` to the
     service URL. On startup the server registers
     `<base>/telegram/webhook/<TELEGRAM_WEBHOOK_SECRET>`.
   - **Polling** (local dev): leave `PUBLIC_BASE_URL` blank — the server long-polls.
4. Voice: works out of the box via **gTTS** (free, no key). For premium voice set
   `ELEVENLABS_API_KEY` (+ optional `ELEVENLABS_VOICE_ID`). True Telegram *voice
   notes* (OGG/Opus) need `ffmpeg` — it's bundled in the Docker image; without it
   Jarvis sends an MP3 audio file instead.

> **Note:** the bot must run somewhere with open internet egress (your machine or a
> deploy). It cannot run inside a sandboxed environment that blocks `api.telegram.org`.

### Quick local start

The bot polls Telegram in long-poll mode locally — no public URL needed:

```bash
cd server
./run_local.sh          # first run scaffolds .env — fill in your token, then:
./run_local.sh          # boots API + dashboard + Telegram polling
```

The script creates a virtualenv, installs deps, verifies your token with Telegram
(`getMe`, prints the bot's @username), and starts the server. Then open
`http://127.0.0.1:8000/dashboard` and DM your bot `/start`.

## Telegram commands

| Command | What it does |
|---------|--------------|
| `/start`, `/help` | Intro + your chat id |
| `/status` | Account snapshot + latest swarm decision |
| `/run [capital]` | Run an agent cycle and send a spoken + text briefing |
| `/positions` | List open positions |
| `/mode` | Show paper/live · `/mode paper` / `/mode live confirm` to switch |
| _free text_ | Ask anything — answered by Gemini grounded in live account context, with a voice reply |

All trading-touching commands are gated to `TELEGRAM_CHAT_ID`. `/start` and `/help`
are open (so a new chat can discover its id) but reveal nothing sensitive.

## Paper ⇄ Live

Trading mode is switchable at **runtime** — no redeploy:

- **Dashboard:** the `Paper` / `Live` segmented buttons in the header.
- **Telegram:** `/mode paper` or `/mode live confirm`.
- **API:** `POST /v1/account/mode {paper, confirm}` — going live requires
  `confirm: true` (real money). `ALPACA_PAPER` remains the startup default; the
  override is process-local and resets on restart.

## Proactive briefings

`POST /v1/agents/run` (and the dashboard **Run Cycle**) auto-push a briefing to your
Telegram when configured. `POST /v1/jarvis/brief` runs a cycle and briefs on demand.
A briefing is a text card (posture, risk gate, recommended actions) plus a short
spoken summary as a voice message.
