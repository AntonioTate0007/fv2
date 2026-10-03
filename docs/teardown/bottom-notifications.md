# Teardown: Bottom notifications (`com.bottomnotifications.app`)

Status: **phase 1 — black-box teardown** (public material only).
Phase 2 (static analysis of the APK) is blocked in the cloud session: the egress
proxy denies `play.google.com` and every APK mirror. See "Next steps".

Legend: **[O]** observed (README / screenshots) · **[I]** inferred from Android platform
constraints — must be confirmed against the decompiled APK.

## 1. Identity

| | |
|---|---|
| Package | `com.bottomnotifications.app` [O] |
| Developer | "Vojislav", solo dev, GitHub `vdb86` (Belgrade, per screenshots) [O] |
| Public repo | `github.com/vdb86/Bottom-notifications` — issue tracker + screenshots only, **no source** [O] |
| Version | 2.3.1 as of 2026-10-03; the in-app "What's new" lists releases back to 1.9 [O] |
| Min SDK | Android 10 (API 29) [O] |
| Monetisation | Free + "Pro" tier (PRO badge, "Bottom notifications Pro — all features available") → Play Billing [O/I] |
| Network | Claims **no INTERNET permission** [O] → license check must be Play Billing via Play Store IPC (no own backend) [I] |
| i18n | 50 locales [O] |

## 2. Core concept

A floating overlay button (usually bottom of screen) opens a full-height **bottom-anchored
notification shade** that mirrors the system's active notifications, plus a rules engine
("notification manager") that silences, holds, batches, or acts on incoming notifications.

## 3. Reconstructed architecture [I]

```
NotificationListenerService  ──►  NotificationRepository (in-memory StateFlow<List<Item>>)
   onNotificationPosted/Removed        │            │
   getActiveNotifications()            │            └─► RulesEngine (evaluate top→bottom on post)
   snoozeNotification()                │                   actions: cancel / snooze / hold+repost /
   cancelNotification()                │                            copy OTP / PendingIntent.send /
   getCurrentRanking() (DND)           │                            RemoteInput reply / TTS / torch / vibrate
                                       ▼
Foreground Service ("Run the service") ── WindowManager overlays (TYPE_APPLICATION_OVERLAY)
   ├─ Floating button view (drag, snap-to-edge, fling, gestures, action wheel, icon cluster)
   ├─ Shade view (list or "ferris wheel", 36 open/close animations)
   └─ Screen-lighting overlay (border / button effects)
AccessibilityService (optional) ── foreground app, IME visibility/bounds, global actions,
                                   ACTION_PASTE into focused node
MediaSessionManager.getActiveSessions(listenerComponent) ── built-in media player
AppWidgetProviders (list widget via RemoteViewsService, icon strip, count) + lock-screen widget
TileService (QS tile "release held notifications") · exported activity/intent for Tasker/MacroDroid
```

### Android components to expect in the manifest [I]
| Component | Evidence |
|---|---|
| `NotificationListenerService` (`BIND_NOTIFICATION_LISTENER_SERVICE`) | "Notification access — connected and listening" |
| Foreground service + `SYSTEM_ALERT_WINDOW` | "Display over other apps", "Run the service", persistent overlay notification with "Toggle shade" actions |
| `AccessibilityService` (`BIND_ACCESSIBILITY_SERVICE`) | Optional; 5 documented uses |
| `PACKAGE_USAGE_STATS` | "Usage access — optional" (foreground-app fallback without a11y) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Battery card links dontkillmyapp.com |
| `TileService` | quick-settings tile |
| `AppWidgetProvider` ×3 | list / icon-strip / count widgets |
| Second launcher `activity-alias` | "Add 'Open shade' app icon" |
| `CAMERA` (torch via `CameraManager.setTorchMode`), `VIBRATE`, `ACCESS_NOTIFICATION_POLICY` (ringer/DND), `READ_CONTACTS` (contact condition), `QUERY_ALL_PACKAGES` (app pickers, icon packs) | rule actions/conditions |
| `com.android.vending.BILLING` | Pro |
| `USE_FULL_SCREEN_INTENT`/`TURN_SCREEN_ON`/`WAKE_LOCK` | "new notification lights up the screen"; lock-screen shade |

## 4. Feature → implementation mapping

### Shade [O]
- Settings: max width/height separately for portrait and landscape; sort newest-at-top or
  at-bottom; background overlay; remember scroll; browsing mode (List / "ferris wheel");
  push/pull to close; start position top/bottom.
- Per-element styling: background, border, header (icon, app name, subtitle with sender
  and conversation title), body (title, text, max lines, large icon, picture), buttons and
  progress bar, media player tint and album art as background.
- Rows render from `Notification.extras` (`EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`,
  `EXTRA_MESSAGES` for MessagingStyle, `EXTRA_PICTURE`, `EXTRA_PROGRESS*`,
  `EXTRA_MEDIA_SESSION`), **not** from `RemoteViews`: that is the only way per-element
  restyling works [I]. Custom-view notifications (the weather widget in the screenshot)
  are probably inflated via `contentView/bigContentView.apply()` as a fallback [I].
- Row actions: swipe dismiss (`cancelNotification(key)`), tap (`contentIntent.send()`),
  inline reply (`RemoteInput.addResultsToIntent`), snooze (`snoozeNotification(key, ms)`),
  "Actions" sheet that regex-extracts OTP codes, URLs, and phone numbers from the text.
- Group by app; "Ungroup grouped notifications" = drop `FLAG_GROUP_SUMMARY` items.
- Filters: include/exclude apps, top/bottom pinned apps, "show only" / "hide matching"
  word lists split by `|`, hide items with no time, hide ongoing (`FLAG_ONGOING_EVENT` /
  `FLAG_FOREGROUND_SERVICE`), DND-aware via `RankingMap.matchesInterruptionFilter()`.
- Per-app overrides: forced colour and max body lines.

### Floating button [O]
- Free-floating or snapped to an edge; separate position when snapped; fling velocity;
  rotate with screen; landscape behaviour; hide in fullscreen apps; keyboard behaviour;
  per-app behaviour (all through the a11y service or UsageStats).
- Appearance: shape (W×H, per-corner radius), background, border, unread count, icon of
  latest notification, charging ring (`ACTION_BATTERY_CHANGED`), animated music art
  (Record/Tape/CD/EQ/…).
- **Notification icon cluster** around the button: line/ring layout, side, direction,
  distance, icon size, max icons with a "+N" overflow, monochrome option (uses
  `Notification.smallIcon`).
- **Gestures**: tap + 4 swipes per "mode"; action wheel with 3 rings. Actions: launch
  app/shortcut (`LauncherApps`), custom intent with a Test button, last app, assistant,
  torch, mute, paste saved text, Back/Home/Recents (`performGlobalAction`).

### Rules engine [O]
- Ordered list, drag to reorder, enable toggle, duplicate, delete, 20 templates.
- **Conditions** (AND/OR, nested groups, invertible): app, words (anywhere / whole word /
  regex), category, importance, group chat (`EXTRA_IS_GROUP_CONVERSATION`), contact
  (`EXTRA_PEOPLE_LIST` → Contacts), has picture, has reply action, text length, time
  windows per weekday, device state (screen on, in call, ringer mode, DND).
- **Actions**: mute/silence, snooze, dismiss, hold and deliver in a batch (scheduled or
  hourly), let the first through then quiet the rest, remind later, custom
  sound/vibration/torch/ringer/TTS, copy OTP to clipboard, press a notification button,
  reply, open, custom lighting style.

How "silence" can work [I]: a listener **cannot** make another app's notification silent
after the fact. The options are (a) `cancelNotification` and re-show it in the app's own
quiet channel, (b) `snoozeNotification` and re-post later (this is what "hold/batch" and
the QS tile's "hand back everything held" suggest), or (c) on API 33+ adjust it as an
`NotificationAssistantService` (not available to Play apps). Expect (a) and (b) together,
with the app re-posting held items as its own notifications. **Confirm in the APK.**

Note: the README says rules can press notification buttons only while the a11y service is
on. That points to Android 14+ background-activity-launch limits on `PendingIntent.send()`
from the background; the a11y service grants a BAL exemption [I].

### Screen lighting [O]
Border effects (Basic, Multicolour, Glow, Echo, Neon, Lightning, Rise, Heartbeat, Drip,
Converge) and button effects (Wave, Bubbles, Fireworks, … Confetti), coloured by the
notification's `color`, the theme, or a custom colour. This needs a full-screen,
non-touchable overlay plus a screen wake lock (or `setTurnScreenOn`) [I].
The 2.3.1 changelog ("fixed taps near the button not reaching the app below while Screen
lighting or the button's appear and hide animation played") confirms the effects draw in
an overlay window larger than the button, so that window must be made pass-through
(`FLAG_NOT_TOUCHABLE`) while an animation plays [O/I].

### Other [O]
Material You (`dynamicDarkColorScheme`), icon-pack support (ADW/Nova `appfilter.xml`
intent filters), custom per-app icons, themes, settings export/import (JSON via SAF),
lock-screen shade (`setShowWhenLocked` activity), debug view showing the raw notification
extras, and a tab bar with General / Notifications / Button / Shade.

## 5. Probable tech stack [I]
Kotlin, Jetpack Compose (settings UI style), DataStore or Room for rules and settings,
WorkManager or AlarmManager for batch delivery, Play Billing. Verify with
`apkid` and by checking for `androidx.compose` and `kotlinx` packages in the dex.

## 6. Next steps (phase 2 — static analysis)
1. Pull the APK from a device: `adb shell pm path com.bottomnotifications.app` and
   `adb pull` each split (base + config splits), or download it from a mirror on an
   unrestricted network. Commit it to a private branch or drop it in the session.
2. `apktool d base.apk`: read `AndroidManifest.xml` to confirm section 3 (permissions,
   services, receivers, `accessibility_service_config.xml`, widget XML).
3. `jadx -d out base.apk`: locate the `NotificationListenerService` subclass, then follow
   `onNotificationPosted` → rules evaluation; read how hold, silence and batch are
   implemented.
4. Check for INTERNET permission and network libraries to verify the privacy claims.
5. Extract the rule JSON schema from export/import, which is the best spec for the rules
   engine.
