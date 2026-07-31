# Jarvis — the on-phone assistant

This is the **native** Jarvis: a voice-first personal assistant that lives inside the
Fortress Android app, separate from the trading screens. It's the "talks to me on my
phone, reminds me of things, tells me what's happening" assistant — no Telegram, no
server required for the core features.

## What it does

| Capability | How | Needs network / key? |
|------------|-----|----------------------|
| **Ask me anything** | Gemini, with live phone context (time + agenda) | Yes — Gemini key (Settings) |
| **Reminders** | Plain-English parse → exact OS alarm → notification + spoken nudge | No |
| **Daily briefing** | Once-a-day alarm composes calendar + reminders, posts + speaks it | No |
| **Calendar / agenda** | Reads the device calendar via `CalendarContract` | No (needs READ_CALENDAR grant) |
| **Voice in** | System speech-recognizer dialog (the mic button) | Device-dependent |
| **Voice out** | Android `TextToSpeech` — every reply is spoken | No |
| **Email** | Not on-device yet — pointer to the server's Gmail integration | (future) |

## How the pieces fit

```
JarvisScreen ──▶ JarvisViewModel ──▶ JarvisRouter
                                       ├─ ReminderParser ─▶ ReminderStore ─▶ ReminderScheduler ─▶ (AlarmManager)
                                       │                                                            └─▶ ReminderReceiver ─▶ JarvisNotifications + VoiceManager
                                       ├─ CalendarReader (CalendarContract)
                                       └─ JarvisBrain (Gemini)

FortressApp / BootReceiver ──▶ re-arm reminders + DailyBriefingScheduler ──▶ DailyBriefingReceiver ──▶ BriefingComposer
VoiceManager ──▶ TextToSpeech (shared, warm engine for UI + alarms)
```

The deterministic intents (reminders, agenda) run with **no network and no API key**, so
the assistant is useful the moment it's installed. The Gemini brain is only consulted for
open questions.

## Try it

- "Remind me to call mom at 5pm" → schedules an alarm, confirms, fires later with sound + voice.
- "Remind me in 20 minutes to take the pasta off."
- "What's on my calendar today?" → reads the device calendar (grant access via the 📅 button).
- "What reminders do I have?"
- "What's a good 20-minute dinner with chicken and rice?" → Gemini.

## Setup notes

- **Gemini key:** Settings → paste your key (same key the Risk Officer uses). Without it,
  reminders/calendar/briefings still work; only "ask anything" is disabled.
- **Calendar:** tap the 📅 button in the Jarvis header to grant `READ_CALENDAR`.
- **Exact alarms:** Android 12+ may require the "Alarms & reminders" special access for
  on-the-dot delivery; the app falls back to inexact alarms if it isn't granted.
- **Daily briefing:** defaults to 8:00 AM (see `JarvisPrefs`). Re-armed automatically each
  day and after reboot.

## Not done yet (honest list)

- **Email** reading is server-side (Gmail) only; the native app points you there for now.
- The briefing time isn't yet exposed in the Settings UI (lives in `JarvisPrefs`).
- Reading *your phone's own* notifications (e.g. summarizing other apps) would need a
  `NotificationListenerService` — a deliberate phase-2 item.
