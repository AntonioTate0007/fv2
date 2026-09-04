# Jarvis Phone

A Jarvis-style personal assistant that runs **on your Android phone**, inside
[Termux](https://termux.dev). It controls the phone through
[Termux:API](https://wiki.termux.com/wiki/Termux:API), listens through Android's
speech recogniser from a **home-screen button** or a **notification**, takes orders
over **Telegram** (text or voice notes) or the terminal, speaks through the phone's
own text-to-speech, and can report on your [Fortress](../server) trading account.
No Android app to build: everything is Termux add-ons plus Python.

The everyday commands never touch a model: a rules router handles them instantly,
offline. Anything it doesn't recognise goes to a **local model** running on the
phone (llama.cpp, Cactus, anything OpenAI-compatible) and, if that's not running,
to **Gemini**. Each layer is optional.

```
 home-screen button ─► speech recogniser ─┐
 notification Talk/Type buttons ──────────┤
 Telegram text / voice note ──────────────┤
 terminal ────────────────────────────────┘
        │
        ▼
    ┌───────┐   rules ──► local model ──► Gemini        (first answer wins)
    │ Agent │ ◄──────────── Plan {tool, args} ───────
    └───┬───┘
        ▼
   Tool registry ──► termux-*  (battery, torch, tts, camera, location, …)
                 ──► Fortress  (/v1/agents/status, /v1/armory/positions, /v1/agents/run)
                 ──► memory    (reminders, notes)
```

## Install (on the phone)

Install **Termux**, **Termux:API** and **Termux:Widget** from F-Droid (the Play
Store builds are abandoned and won't work). Optionally **Termux:Boot** for
autostart. Then in Termux:

```bash
curl -sSL https://raw.githubusercontent.com/AntonioTate0007/fv2/main/phone/setup-termux.sh | bash
```

That installs packages, clones this repo to `~/fortress`, creates `phone/.env`, and
drops a Termux:Boot script. Then:

```bash
cd ~/fortress/phone
nano .env                          # TELEGRAM_BOT_TOKEN from @BotFather
python -m jarvis_phone doctor      # checks Termux:API, brain, Telegram, Fortress
./run.sh                           # start the bot
```

Message your bot `/start`. It replies with your chat id; put that in
`TELEGRAM_CHAT_ID` and restart. From then on it obeys only that chat.

No Telegram? Talk to it in the terminal: `python -m jarvis_phone chat`.

## Talking to it on the phone

- **Home-screen button.** Long-press the launcher → Widgets → *Termux:Widget* →
  drop the **Jarvis** shortcut. Tap it: Android's speech recogniser opens, you
  speak, Jarvis acts and answers out loud (plus a toast). **Jarvis-Conversation**
  keeps the mic open after each answer until you say "bye". **Jarvis-Type** uses a
  text box instead of the mic.
- **Notification panel.** While the bot runs, a sticky low-priority notification
  offers **🎤 Talk** and **⌨️ Type** buttons, so it is one swipe away on any screen.
- **Telegram voice notes.** Hold the mic in Telegram and talk. With
  `GEMINI_API_KEY` set the note is transcribed, echoed back as text, and handled
  like a typed message. Without a key you get a polite "type it instead".
- **Confirmations work by voice too.** "Text mom: running late" → "Reply yes to
  confirm" → say "yes".

What it is not: there is no always-on wake word. Termux cannot keep a
microphone open in the background, so listening starts from a tap.

## The floating ring (Jarvis Overlay)

`phone/overlay/` is a tiny **separate** Android app (plain Views, zero
dependencies, nothing to do with the Fortress app) that floats an arc-reactor
ring over every screen:

- **Idle:** translucent, slow tick rotation, faint breathing.
- **Listening:** full opacity, a wave runs around the ring.
- **Thinking:** three arcs orbit.
- **Speaking:** the bars jump like an audio spectrum, the core pulses, and the
  last sentence appears in a caption under the ring, then fades.
- **Drag** it anywhere (it snaps to the nearest side). **Tap** to talk: the ring
  runs `~/.shortcuts/Jarvis` inside Termux. **Long-press** for its settings.
- Its notification also carries a *Talk* button.

Jarvis drives it with Android broadcasts through Termux's `am` command; the
`overlay.py` bridge wraps every spoken reply and the tap-to-talk session, so
the ring lights up for exactly as long as the phone is talking. With no app or no
`am`, the calls are silent no-ops.

Install:

1. Get the APK: the **overlay-apk** GitHub Action builds it on every push that
   touches `phone/overlay/` (download it from the workflow run's artifacts), or
   open `phone/overlay` in Android Studio and run *assembleDebug*.
2. Install it, open it, grant **draw over other apps** and **run Termux
   commands**, tap **Start floating Jarvis**. The *Test* button cycles the states.
3. `setup-termux.sh` already sets `allow-external-apps = true` in
   `~/.termux/termux.properties`, which Termux requires before another app can
   launch a script. Re-run the installer (or add the line) if you set up Termux
   before this feature existed.
4. From Termux, `python -m jarvis_phone overlay speaking "hello"` drives the
   ring by hand. Battery-wise: the ring renders at a low frame rate when idle.

## What it understands

| Say | Does |
|-----|------|
| battery · wifi · where am I · device status · what time is it | reads sensors |
| flashlight on / torch off · vibrate · brightness 40% · volume 20% · mute the ringer | phone controls |
| say good morning · notify me: drink water · copy this: text · clipboard | speaker, notifications, clipboard |
| take a photo · selfie · open youtube.com | camera (sends the picture back), browser |
| remind me in 20 minutes to call mom · remind me at 6:30pm to … · reminders · cancel reminders | reminders fire as a Telegram message, a notification, and out loud |
| note: buy milk · remember the gate code is 4412 · notes · clear notes | notes |
| how am I doing · positions · run the swarm at 2500 | Fortress account, open spreads, agent cycle |
| text +15551234567: running late | SMS (off by default, always asks to confirm) |
| anything else | goes to the model, which can also pick a tool |

Commands: `/help` `/tools` `/brain` (which brains are up) `/speak on|off` (read every
Telegram reply aloud on the phone) `/forget` (wipe short-term memory) `/whoami`.
Voice sessions always speak, regardless of `/speak`.

Dangerous tools (`sms_send`, `clear_notes`) ask for a **yes** before running. The
confirmation is per chat and expires after two minutes.

## Brains

Set `JARVIS_BRAIN` in `.env`:

| Value | Behaviour |
|-------|-----------|
| `auto` (default) | rules → local model (if `LOCAL_LLM_URL` answers) → Gemini (if `GEMINI_API_KEY` set) |
| `rules` | regex intents only. Zero latency, zero network, zero cost. |
| `local` | rules, then the on-phone model |
| `gemini` | rules, then Gemini |

### Running a local model

Any OpenAI-compatible server works. With llama.cpp from the Termux repo:

```bash
pkg install llama-cpp
mkdir -p ~/models && cd ~/models
# download a small instruct GGUF here — 0.5B–1.5B parameter models run fine on a phone;
# the Cactus "Needle" model from the video is one option, Qwen2.5-0.5B-Instruct another.
cd ~/fortress/phone && ./run.sh model ~/models/<your-model>.gguf
```

The brain speaks to the model with **prompt-based JSON tool calling** (the system
prompt lists the tools; the model answers with one JSON object). That is what makes
tiny models usable: they don't need native function-calling support, and the parser
tolerates code fences, chatter, and OpenAI-style `name`/`arguments` shapes. An
unknown tool name degrades to a text reply rather than an error.

Keep expectations realistic: a sub-1B model is good at picking a tool and filling
arguments, and bad at reasoning. Anything that touches money stays on Fortress's
server-side agents.

## Fortress link

Point `FORTRESS_URL` at your Fortress backend (the Pi, Render, or a phone running
`server/`) and `FORTRESS_API_TOKEN` at its bearer token. Jarvis then answers *how
am I doing*, *positions*, and *run the swarm* by calling `/v1/agents/status`,
`/v1/armory/positions` and `/v1/agents/run`. Paper vs. live follows the server's
mode; this assistant never flips it.

## Keeping it alive on Android

- **Battery:** Settings → Apps → Termux → Battery → *Unrestricted*. Android 12+
  will otherwise kill the process minutes after the screen locks.
- **Wake lock:** `run.sh` acquires `termux-wake-lock` on start.
- **Autostart:** open the Termux:Boot app once after installing; the installer
  drops `~/.termux/boot/jarvis.sh`, which starts `run.sh` at boot. Logs land in
  `~/jarvis.log`.
- **Crashes:** `run.sh` supervises the bot and restarts it after five seconds,
  except when it exits because the Telegram token is missing.

## Security

- Only `TELEGRAM_CHAT_ID` can issue commands. Everyone else gets their chat id and
  nothing more. With no owner configured, *nobody* is trusted.
- SMS is disabled unless `JARVIS_ALLOW_SMS=on`, and always confirms.
- Nothing listens on the network. The bot long-polls Telegram outbound; the local
  model server binds to localhost.
- `.env` lives on the phone only and is git-ignored.

## Development

```bash
cd phone
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt pytest pytest-asyncio
pytest                        # all Termux calls are faked
python -m jarvis_phone once "battery"   # off-phone: "Phone says no: termux-… isn't installed"
```

Layout:

```
jarvis_phone/
  agent.py        message → brain → confirm? → tool → replies (transport-agnostic)
  tools.py        the tool registry; one function per capability
  termux.py       subprocess wrappers around termux-* commands
  brain/
    rules.py      regex intent router
    llm.py        LocalLLMBrain (OpenAI-compatible) + GeminiBrain + shared JSON parser
    auto.py       chain-of-brains + factory
  telegram.py     long-polling bot, owner gate, /commands, voice-note transcription
  voice.py        tap-to-talk session (termux-dialog speech → agent → termux-tts-speak)
  transcribe.py   Gemini speech-to-text for Telegram voice notes
  scheduler.py    reminders + optional morning brief
  memory.py       JSON store: history, notes, reminders, prefs
  fortress.py     client for server/main.py
  overlay.py      broadcasts idle/listening/thinking/speaking to the floating ring app
  __main__.py     bot | chat | doctor | once | listen | type | panel | overlay
overlay/          the floating-ring Android app (standalone Gradle project)
```

Adding a tool is one `reg.add(Tool(...))` in `tools.py` plus, optionally, a regex in
`rules.py`. The LLM brains pick it up from the registry automatically.
