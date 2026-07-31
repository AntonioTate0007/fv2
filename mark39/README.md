# Mark XXXIX — Android edition

A standalone **Android** port of the assistant idea from
[FatihMakes/Mark-XXXIX-OR](https://github.com/FatihMakes/Mark-XXXIX-OR) — a voice-first,
Jarvis-style personal AI that lives on your phone. This is its **own** app (package
`com.mark39.assistant`), completely separate from anything else in this repo.

## What it does

- **Talk to it** — tap the reactor button, speak (system speech recognizer, no extra
  permission), and Mark replies **out loud** (text-to-speech) and in text.
- **Ask anything** — answers via **Gemini** or **OpenRouter** free-tier models; pick a
  preferred brain in Settings, with automatic fallback to the other if a key is present.
- **Reminders** — plain-English ("remind me to call mom at 5pm", "in 20 minutes") parsed
  on-device, scheduled as exact OS alarms, fired with a notification + spoken nudge, and
  re-armed after reboot.
- **Memory** — "remember that my anniversary is June 2"; Mark keeps short facts about you
  and grounds future answers in them. "What do you remember?" / "forget everything".

Reminders and memory work with **no network and no API key**; only "ask anything" needs a key.

## Why it isn't a 1:1 port

The original Mark-XXXIX-OR is a **desktop** app whose core power is controlling a computer
(launching apps, managing files, running shell commands, screen-watching via Playwright).
Android sandboxes apps by design, so those PC-control abilities **cannot** run on a phone.
This edition keeps the assistant brain (voice + LLM + memory + reminders) and drops
desktop control. (A future "companion" mode could let the phone drive a PC that's running
the original — not built here.)

## Build

This is a self-contained Gradle project:

```bash
cd mark39
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/mark39-build.yml`) builds the APK on every push and publishes it to
the `mark39-latest` release for sideloading.

## Setup on the phone

1. Install the APK (allow "install from unknown sources").
2. Open **Settings** in the app, paste a free **Gemini** key (aistudio.google.com/apikey)
   and/or **OpenRouter** key (openrouter.ai/keys), pick your preferred brain, Save.
3. Tap the mic and talk.

## Credit & license

Inspired by and a mobile companion to **FatihMakes' Mark-XXXIX-OR**, which is licensed
**Creative Commons BY-NC 4.0** (attribution, non-commercial, personal use). This port is
for personal use and credits the original author. If you use or share it, keep the
attribution and don't use it commercially.
