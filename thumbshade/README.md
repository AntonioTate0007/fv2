# ThumbShade

Your notifications at the bottom of the screen, where your thumb is. A floating button opens a
notification shade that slides up from the bottom, with a rules engine that silences, holds,
batches and acts on notifications for you. Every feature is free. The app has no internet
permission, so nothing leaves the phone.

This is an original app written from scratch. Its feature set follows the study in
[`docs/teardown/bottom-notifications.md`](../docs/teardown/bottom-notifications.md). It contains no
code, name, icon or artwork from that app.

## Install

Every push builds the APKs on GitHub Actions (workflow **ThumbShade APK**). Open the latest run,
download the `thumbshade-apk` artifact, unzip it and install `app-release.apk` (or `app-debug.apk`).
The release build is signed with the debug key, so you may need to allow installs from your
browser or file manager.

Then open ThumbShade and grant, on the **General** tab:

| Permission | Why |
|---|---|
| Notification access | Read and manage notifications (required) |
| Display over other apps | The button, the shade and screen lighting (required) |
| Post notifications | The "running" notice and rule reminders |
| Accessibility service *(optional)* | Keyboard-aware and per-app button, Back/Home/Recents gestures, pasting saved texts, pressing notification buttons from rules. It reads no screen content. |
| Battery optimisation off *(optional)* | Keeps the service alive on aggressive phones |
| Do Not Disturb access *(optional)* | Rules that change the ringer |
| Exact alarms *(optional)* | Batches and reminders on the minute |
| Contacts *(optional)* | The "from a saved contact" rule condition |

## Features

**Floating button**: drag (long-press, then move), snap to edge, size, shape, corner radius,
colour, opacity and border. It can show a count, the latest app's icon, album art while music
plays and a charging ring, and it pulses on new notifications. It can move above the keyboard or
hide while you type, hide in apps you choose, hide when empty, or "hide for a while". An icon
cluster shows the latest apps' icons in a ring, above, or beside the button.

**Gestures**: tap plus four swipe directions per *mode*; switch modes with a gesture. Gestures can:
toggle the shade, open the newest notification, open an app, Back, Home, Recents, previous app,
system shade, quick settings, assistant, torch, mute, paste a saved text, screenshot, lock
screen, hide the button, clear all, release held notifications, play/pause, or skip to the next track.

**Shade**: newest nearest the thumb, max height and width, background dim, list or "ferris
wheel" browsing, and 6 open/close animations. Swipe to dismiss, tap to open, inline reply,
notification buttons, snooze (presets plus a custom length), and an **Actions** panel with the
verification code, links and phone numbers found in a notification, plus copy and share. Long-press
a notification to pin its app top or bottom, group it, hide it, make a rule for it, open app info,
or clear the app. Per-element styling: card colour and corners, body lines, large icon, pictures, buttons.

**Media player**: play/pause, previous/next, seek bar, ±10 s, the player's own custom buttons
(shuffle, repeat, like…), and album art as the background.

**Filtering**: include/exclude apps, top/bottom pinned apps, group an app into one row, ungroup
bundles, hide permanent or time-less notifications, follow Do Not Disturb, and a "show only" /
"hide matching" word filter. Per-app colour and line-count overrides.

**Rules** (top to bottom, reorderable, duplicable, 21 templates):
- *Conditions* (AND/OR, nested groups, each invertible): words (contains, whole word, regex)
  in title/text/app name, category (19 kinds), importance range, group chat, from a contact,
  has picture, can reply, ongoing, silent, message reaction, time of day per weekday (wraps past
  midnight), text length, screen on, in a call, ringer mode, Do Not Disturb, charging.
- *Actions*: silence, snooze, dismiss, hold and deliver in a batch (at set times or every N
  minutes), let the first through and quiet the rest, remind later, custom sound and vibration,
  flash the torch, change the ringer, change Do Not Disturb, read aloud, copy the verification code,
  press a notification button, send a reply, open it, light up the screen in a chosen style.
- A **quick-settings tile** and the Rules tab release everything held. The Rules tab also logs what each rule did.

**Screen lighting**: border effects (Basic, Multicolour, Glow, Heartbeat, Neon, Comet) and
button effects (Ripple, Sonar, Halo). Colour comes from the notification, the theme, or a custom
colour. Rules can trigger their own style.

**Elsewhere**: lock-screen shade, an optional second "Open shade" launcher icon (for launchers,
Tasker or MacroDroid), home and lock-screen widgets (list, icon strip, count), Material You
colours, dark, light and pure-black themes, and settings + rules backup to a JSON file. It can also
snooze Android's "displaying over other apps" notice.

## How the tricky parts work

- **Silencing someone else's notification.** A listener can't change another app's alert.
  ThumbShade sets the listener hint `HINT_HOST_DISABLE_NOTIFICATION_EFFECTS` for as long as the
  notification's sound lasts (1–7 s, extended while more muted notifications arrive, capped at 15 s,
  then a 30 s cooldown). On Android 11+ it also snoozes the notification for 300 ms, which cuts a
  sound that already started. The notification comes straight back quietly, and the shade
  hides the blink.
- **Holding / batching** snoozes the notification until the delivery time; the system brings it
  back. An early release re-snoozes it for 10 ms because listeners have no "unsnooze".
- **Overlay windows.** The button, the icon cluster (not touchable) and the shade are
  `TYPE_APPLICATION_OVERLAY` windows rendered with Compose, owned by a `specialUse` foreground
  service.

## Build

```
cd thumbshade
./gradlew testDebugUnitTest assembleDebug
```

Needs JDK 17+ and the Android SDK (platform 35).

## Not included

Compared with the app it was modelled on, ThumbShade doesn't have: the 36 open/close animations
(it has 6), the three-ring action wheel (it uses swipe modes), icon-pack support and custom per-app
icons, translations (English only), or the full set of lighting effects (9 of about 23).
