# Plays App

An Android app (Jetpack Compose) that shows your **real Alpaca** account balance
and open positions. It talks to Alpaca's REST API **directly from the device** —
there is no backend in between, and your keys never leave the phone.

- Package / applicationId: `com.fortress.trader`
- Min Android version: 8.0 (API 26)
- App name on device: **Plays**

## What's live

Pulled from Alpaca (`/v2/account`, `/v2/positions`):

- Portfolio value, cash, and today's P/L.
- Option positions, grouped by underlying + expiration into **spreads** — each
  card shows the strikes (parsed from the OCC option symbols), expiration,
  contract count, net market value, and unrealized P/L.
- Share (equity) positions.

> Note: Alpaca has no earnings-calendar endpoint, so the old "earnings catalyst"
> banner from the mock was removed rather than faked. The card shows the real
> **expiration** date instead.

## Connecting your account

1. Install the app (see below) and open the **Settings** tab.
2. Paste your **Alpaca API Key** and **Secret**.
3. Leave **Paper trading** on to use `paper-api.alpaca.markets`, or turn it off
   to use the **live** account (`api.alpaca.markets`).
4. Tap **Save and Connect**. The Plays tab loads your account and positions; use
   the refresh icon to reload.

Keys are stored only on-device in the app's private DataStore (sandboxed from
other apps) and are sent only to Alpaca over HTTPS.

## Getting the APK on your phone

CI (`.github/workflows/plays-app-build.yml`) builds the APK on every push and
attaches it to a pre-release named **"Plays App (latest debug build)"**
(tag `plays-app-latest`).

1. Open the repo's **Releases** page on your phone.
2. Open **Plays App (latest debug build)** and download `app-debug.apk`.
3. Tap to install (allow **"install unknown apps"** for your browser if asked).

## Build it yourself

```bash
cd plays-app
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Or open the `plays-app` folder in Android Studio and press Run.
