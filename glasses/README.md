# Jarvis — Grok in your Meta glasses

An Android app that turns Ray-Ban / Oakley Meta glasses into **J.A.R.V.I.S.**:
say **"Jarvis"** and ask anything. Jarvis sees what you see (glasses camera),
thinks with **Grok** (xAI), and answers in your ear in Grok's voice.

```
"Jarvis"  ──►  phone mic hears your question ─┐
              glasses camera grabs sharpest   ├─►  Grok (xAI API)  ──►  Grok voice (xAI TTS)
              photo while you talk           ─┘                         plays in the glasses' speakers
                                                    ▲
              follow-up?  (keeps listening a few seconds, no wake word needed)
```

No jailbreak needed: it uses Meta's official
[Wearables Device Access Toolkit](https://wearables.developer.meta.com/) (v1.0.0, Maven Central).

## Ways to summon Jarvis

| Trigger | Needs |
|---|---|
| Say **"Jarvis"** | Free Picovoice AccessKey (wake word runs on the phone, offline) |
| **Tap the glasses' touchpad** (when no music is playing) | Nothing |
| **Talk** button in the notification or the app | Nothing |
| Glasses button / gesture events (models that report them) | Nothing |

Say "Jarvis" or tap again while he's talking to interrupt. Say "stop", "never mind" or
"that's all" to end the conversation.

## What you need

- Ray-Ban Meta or Oakley Meta glasses, paired in the **Meta AI** app
- Android phone, Android 10 or newer
- [Android Studio](https://developer.android.com/studio) on a computer + a USB cable
- **xAI API key** — <https://console.x.ai> (pay-as-you-go; a question with a photo + spoken
  answer costs a fraction of a cent to a couple of cents)
- **Picovoice AccessKey** (free, optional) — <https://console.picovoice.ai> for the "Jarvis" wake word

## Setup

1. **Turn on Developer Mode for the glasses** in the Meta AI app
   (Meta's docs: Settings → App info → tap the version number until developer mode unlocks,
   then enable it). Developer Mode lets an unpublished app like this one talk to your glasses.
2. **Phone:** Settings → About phone → tap *Build number* 7 times → Developer options →
   enable **USB debugging**. Plug the phone into your computer.
3. **Optional:** create `glasses/local.properties` so the keys are baked into your build
   (this file is git-ignored):
   ```properties
   xai.api.key=xai-...
   picovoice.access.key=...
   ```
   You can also just type the keys into the app.
4. Open the **`glasses/`** folder (not the repo root) in Android Studio, let Gradle sync,
   pick your phone and press **Run ▶**.
5. In the app, top to bottom:
   1. **Grant phone permissions** (microphone, Bluetooth, notifications)
   2. **Link glasses** → Meta AI opens → approve *Jarvis* → you're sent back
   3. **Allow glasses camera** → approve in Meta AI
   4. Paste your **xAI** and **Picovoice** keys → **Save settings**
   5. **Start Jarvis**

Put the glasses on and say **"Jarvis… what am I looking at?"**

## Settings

| Setting | Default | Notes |
|---|---|---|
| Grok model | `grok-4.7` | Any **vision-capable** model id from <https://docs.x.ai/developers/models>. A 404 error means the id is wrong. |
| Personality | `jarvis` | `ultron` = cold, theatrical, menacing wit (still helpful), deeper `rex` voice |
| Grok voice | `leo` (Jarvis) / `rex` (Ultron) | `leo` (authoritative), `rex` (confident), `sal`, `ara`, `eve` |
| Jarvis calls you | `sir` | "ma'am", "boss", your name… |
| Speak with Grok's voice | on | Off = the phone's free offline British TTS voice (also used automatically if xAI TTS fails) |
| Keep listening for follow-ups | on | After answering, listens again without the wake word until you go quiet |
| Send what the glasses see | on | Off = voice-only Grok, no camera |

### About voices

The voices are xAI's stock voices plus a pitched-down phone voice. The app doesn't clone
any actor's voice (for example James Spader's Ultron, or Paul Bettany's JARVIS). Copying a
real person's voice without their permission raises consent and publicity-rights problems.
The wake word stays "Jarvis" (Porcupine's built-in keyword). A custom "Ultron" wake word
can be trained for free in the Picovoice console, but the app would need a small change to load it.

## Privacy

- The wake word runs **on the phone**. Nothing is recorded until you say "Jarvis".
- Your speech is transcribed by Android's speech recognizer (on-device on most recent phones).
- Your question and photo go **only to xAI** (Grok). Meta AI is not involved in answering.
- The photo is taken through Meta's SDK. Meta's terms decide whether the glasses
  report anything about that, the same caveat as in the video.
- Meta SDK analytics and crash reporting are **opted out** in the manifest.

## Troubleshooting

- **"Glasses: not linked"**: make sure Developer Mode is on in Meta AI, then tap *Link glasses* again.
- **No photo / Jarvis says he can't see**: tap *Allow glasses camera*. The glasses must be worn
  (not in the case) and not hot.
- **Wake word doesn't trigger**: check the Picovoice key, and that the phone isn't in
  battery-saver mode that kills background apps (set Jarvis to *Unrestricted* battery use).
- **Sound comes out of the phone**: the glasses must be connected as a Bluetooth audio device.
- **Touchpad tap pauses music instead**: by design. If music is playing, the tap goes to the music app.

## Code map

| File | Job |
|---|---|
| `JarvisService.kt` | Foreground service: wake word → listen + photo → Grok → speak → follow-ups |
| `GlassesLink.kt` | Meta SDK session, camera stream, sharpest-of-N photo, glasses input events |
| `GrokClient.kt` | xAI chat completions (with image + memory) and xAI text-to-speech |
| `Speaker.kt` | Plays Grok's MP3 voice, or a British Android TTS fallback |
| `Listener.kt` | Android speech-to-text on the phone mic |
| `WakeWord.kt` | Porcupine built-in "Jarvis" hotword |
| `MainActivity.kt` | Setup screen |
