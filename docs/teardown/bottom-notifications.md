# Teardown: Bottom notifications (`com.bottomnotifications.app`)

Status: **phase 2: static analysis of the APK done**.
Input: `base.apk` v2.3.1, extracted from a device (Play-distributed, 5.0 MB, one `classes.dex`).
The ABI and density splits were not provided, but they hold only resources and native
libraries; all code is in the base.
Tools: apktool 2.10.0 (manifest/resources), jadx 1.5.1 (Java).
The APK itself is **not** committed (copyrighted binary).

Legend: **[C]** confirmed in code/manifest · **[O]** observed in README/screenshots ·
**[I]** still inferred.

## 1. Identity

| | |
|---|---|
| Package | `com.bottomnotifications.app` [C] |
| Developer | "Vojislav", GitHub `vdb86` (Belgrade, per screenshots) [O] |
| Public repo | `github.com/vdb86/Bottom-notifications`: issue tracker + screenshots only, **no source** [O] |
| Version | 2.3.1 (versionCode 26); the in-app "What's new" lists releases back to 1.9 [O] |
| SDK | compile/target SDK 36 (Android 16). This APK says `minSdk 32`, but README says Android 10+. Play serves per-device variants, so the 32 is probably this device's variant [C/I] |
| Language/UI | Kotlin + coroutines; Jetpack Compose (Material 3); Room present [C] |
| Obfuscation | R8. UI and helpers are flattened into ~3,900 classes in the default package. Components and the whole `notif.rules` package keep real names (needed for JSON and reflection) [C] |
| Protection | Google **PairIP** license check (`com.pairip.application.Application` calls `LicenseClient.checkLicense()` and then `BnApplication`). License check only; no VM-encrypted classes [C] |
| Monetisation | One-time in-app purchase, product id **`bottomnotifications_pro`** (type `inapp`), Play Billing Library 9.0.0 [C] |
| Sister app | Declares `<queries>` for `com.omnideck.app` and can fire its intents (`SHOW_LEFT/RIGHT`, `TOGGLE_POPUP`, `SHOW_APP_INDEX`) as button gestures. Likely the same developer's edge-panel app [C/I] |

## 2. Privacy claims vs. reality

| Claim (README) | Finding |
|---|---|
| "NO internet access" | **Not literally true.** The manifest declares `INTERNET` and `ACCESS_NETWORK_STATE`, merged in from Play Billing and Google `datatransport` [C] |
| Notifications never leave the device | **Holds as far as static analysis shows.** The only `HttpURLConnection` in the dex is Google `datatransport` 3.1.8 (CCT backend, log source `PLAY_BILLING_LIBRARY`): Play Billing's own usage telemetry to Google. No app class that touches `StatusBarNotification` calls networking code. No Firebase, Crashlytics, analytics or ad SDKs [C] |
| Accessibility reads nothing | **Holds.** It listens only for focus, window-state and windows-changed events. It reads the foreground window's package name, whether an IME window (type 2) is present plus its top edge, and whether a focused node is editable. It never calls `getText()` [C] |

## 3. Manifest (confirmed)

Permissions: `BIND_NOTIFICATION_LISTENER_SERVICE`, `SYSTEM_ALERT_WINDOW`,
`FOREGROUND_SERVICE(_SPECIAL_USE)`, `POST_NOTIFICATIONS`, `VIBRATE`, `WAKE_LOCK`,
`SCHEDULE_EXACT_ALARM`, `ACCESS_NOTIFICATION_POLICY`, `READ_CONTACTS`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS`,
`RECEIVE_BOOT_COMPLETED`, `BILLING`, `CHECK_LICENSE`, `INTERNET`, `ACCESS_NETWORK_STATE`.
No `CAMERA`: `setTorchMode` doesn't need it.

| Component | Class |
|---|---|
| Notification listener | `notif.BnNotificationListener` |
| Overlay host (FGS `specialUse`) | `overlay.OverlayService`: "Hosts the user-configured floating notification button and the bottom notification shade overlay window" |
| Accessibility | `access.BnAccessibilityService` (`canRetrieveWindowContent`, `flagRetrieveInteractiveWindows`) |
| QS tile | `notif.rules.ReleaseTileService`: releases held notifications |
| Alarms | `notif.rules.RulesAlarmReceiver` (batch times, reminders) |
| Boot | `overlay.BootReceiver` (BOOT_COMPLETED, MY_PACKAGE_REPLACED, QUICKBOOT) |
| Widgets | `ShadeWidgetProvider` (+ `ShadeWidgetService` RemoteViews list), `LineWidgetProvider`, `LineWidgetVProvider`, `CountWidgetProvider`; each has a config activity |
| Lock-screen shade | `overlay.shade.ShadeHostActivity` (`showWhenLocked`, `turnScreenOn`, singleInstance) |
| "Open shade" launcher icon | `activity-alias OpenShadeAlias` (disabled by default, toggled in settings) |
| Automation entry | `overlay.shade.ShowShadeActivity` (exported; Tasker/MacroDroid) |
| Widget helpers | `WidgetReplyActivity`, `WidgetOpenActivity` (transparent, noHistory) |
| Screens | `OnboardingActivity` (launcher), `MainActivity`, `ThemeEditorActivity`, `AboutActivity`, `WhatsNewActivity`, `LockscreenAppearanceActivity`, `ProPurchaseActivity`, `WidgetManagementActivity` |

Broadcast actions: `ACTION_TOGGLE_SHADE`, `ACTION_TOGGLE_HIDE_OVERLAY`, `NEW_RULE_FOR_APP`,
`RULE_REMINDER`, `WIDGET_ITEM_CLICK`, `LINE_WIDGET_OPEN` (all prefixed
`com.bottomnotifications.app.`).

## 4. Architecture (confirmed)

```
BnNotificationListener
  onListenerConnected  → NotificationStore.replaceAll(active, ranking); RuleEngine.onListenerConnected
                         OverlayWarningHider.sweep(active)
  onNotificationPosted → OverlayWarningHider.maybeHide()  (drops Android's "displaying over other apps")
                         NotificationStore.onPosted()      (StateFlow<List<StoredNotification>> → UI/widgets)
                         RuleEngine.onPosted()             (rules, top→bottom)
                         edge-lighting trigger
  onNotificationRemoved→ NotificationStore.onRemoved; RuleEngine.onRemoved(key, reason)
  onListenerDisconnected → requestRebind() (self-heal)
  Companion helpers: cancel, cancelAll, snooze(key, ms), snoozedKeys(),
                     suppressEffects()/clearEffectSuppression(), requestFilter(DND), forceRebind()
```

`NotificationStore` is a singleton in-memory store. `StoredNotification` and
`NotificationGroup` are the shade's model. `NotificationRoundTrips` tracks notifications
the app snoozes and expects to come back, so the shade doesn't flicker or treat them as new.

## 5. How the clever parts actually work

### Mute: no re-post. It suppresses system effects for the length of the sound [C]
A listener can't silence another app's notification, so `MuteController` does this:
1. Measures the notification's sound (`NotificationSoundDurationKt.computeMuteWindowMs`:
   1–7 s clamp, 3 s fallback, +300 ms tail).
2. Opens a **mute window**: `requestListenerHints(HINT_HOST_DISABLE_NOTIFICATION_EFFECTS)`,
   which makes the system stop notification sound and vibration while the hint is set.
   Optionally it also opens a short **DND window** (`ZenWindow`, via `requestInterruptionFilter`).
3. On API 30+, adds a **300 ms "snooze layer"**: the notification is snoozed for 300 ms and
   comes straight back, which kills any alert already playing. `SnoozeLedger.allowSnooze()`
   blocks this for notifications that keep bouncing.
4. A second matching notification during the window extends it. The window is capped at
   **15 s**, followed by a **30 s cooldown**; then it clears the hint and closes DND.

### Hold / batch / snooze: built on `snoozeNotification` [C]
- `BATCH_UNTIL` and `SNOOZE_FOR` call `snoozeNotification(key, ms)` until the next batch time
  (`RuleBatchMode` `TIMES` or `INTERVAL`; `BatchWindowsKt`, `RuleScheduler`, exact alarms).
- `SnoozeLedger` records every app-initiated snooze with a `SnoozeReason`
  (`MUTE`, `SNOOZE_FOR`, `DISMISS_FALLBACK`, `BATCH`, `USER`, `OVERLAY_WARNING`).
- **Release:** listeners have no "unsnooze" API, so `HeldRelease.releaseAll()` re-snoozes
  each held key for **10 ms**, and the system re-posts it almost at once. The QS tile and
  scheduled batch times call this.

### Hiding the "displaying over other apps" warning [C]
`OverlayWarningHider` matches Android's own `AlertWindowNotification` (package `android`,
tag/channel naming this app) and snoozes it (`SnoozeReason.OVERLAY_WARNING`). Turning the
setting off releases it with a 10 ms snooze. This is how the app avoids the permanent
system notification that every overlay app normally gets.

### Cooldown ("let the first through") [C]
`CooldownLedger` and `RuleCooldownMatch`/`RuleCooldownBehavior` (`MUTE` | `DISMISS`): after
the first match, later matches within the window are muted or dismissed.

## 6. Rules engine model (confirmed)

- **Actions** (`RuleActionType`): `MUTE`, `SNOOZE_FOR`, `DISMISS`, `PRESS_BUTTON`, `OPEN`,
  `REPLY`, `CUSTOM_ALERT` (`AlertPlayer`: sound and vibration), `BATCH_UNTIL`, `REMINDER`,
  `TORCH` (`TorchController`), `SET_RINGER` (`RingerController`), `SPEAK` (`SpeechPlayer`,
  TTS), `COOLDOWN`, `SET_DND` (`DndController`), `COPY_TEXT` (`ClipboardCopier`, OTP),
  `EDGE_LIGHT`.
- **Conditions** (`RuleConditionType`): `TEXT_CONTAINS`, `TEXT_NOT_CONTAINS`, `TEXT_REGEX`,
  `CATEGORY`, `IMPORTANCE_RANGE`, `FLAGS`, `TIME_WINDOW`, `TEXT_LENGTH`, `DEVICE_STATE`.
  They live in an AND/OR tree (`RuleGroup`/`RuleNode`, `not` flag).
- **Categories** (`RuleCategory`, the app's own classifier): CALL, MESSAGE, EMAIL, SOCIAL,
  EVENT, REMINDER, ALARM, PROMO, PROGRESS, TRANSPORT, NAVIGATION, MISSED_CALL, SYSTEM,
  SERVICE, ERROR, STATUS, RECOMMENDATION, WORKOUT, STOPWATCH.
- **Device state** (`RuleDeviceAspect`): SCREEN, CALL (`CallGuard`), DND, RINGER, MIC.
- **Contact matching:** `READ_CONTACTS` + `ContactResult`.
- **Delayed dismiss** (`DelayedDismiss`) and **reminders** (`ReminderCommand`, alarm).
- `RuleLog` keeps an in-app log of what each rule did.
- **Serialization** (`RuleJsonKt`) uses compact keys, which is the export/import format:
  `root, kids, conds, acts, apps, apps_ex, match, not, en, name, ord, re, rg, t, w, ww, wm,
  tm, td, ts, te, imin, imax, cat, dev, snd, vib, sl, dnd, cd, cdm, cdb, bm, bi, bt, btn,
  msg, dly, rm, rr, el*, …`.

## 7. Overlays and accessibility [C]
- `OverlayService` is a `specialUse` foreground service. It owns the
  `TYPE_APPLICATION_OVERLAY` windows for the button, the shade and the lighting effects
  (built with `WindowManager.LayoutParams` in several obfuscated UI classes).
- The accessibility service publishes `(foregroundPackage, keyboardVisible, keyboardTop)`.
  On window changes it re-checks after 100/300/600/1000 ms, and after 50/200/500/900 ms when
  an editable field gains focus. The button logic uses this for per-app and keyboard
  behaviour. Global actions (Back/Home/Recents), paste, and pressing notification buttons
  go through the same service.

## 8. What's left / ideas
- The Compose UI is fully obfuscated. Mapping screens to classes is possible but slow, and
  the screenshots already document the UI.
- The rule JSON schema (section 6) is enough to write rules outside the app and import them.
- To build a similar app, the reusable techniques are: mute through listener hints, release
  through a 10 ms re-snooze, hiding the overlay warning through a snooze, and a
  `specialUse` FGS that hosts the overlays.
