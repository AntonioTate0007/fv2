# Plays App

A self-contained Android app built from the Fortress Options "Plays" dashboard
mock. It is a single-screen Jetpack Compose UI (Plays / Settings tabs working,
other tabs are placeholders) with static demo data — no live trading, no
network calls. This is a visual build you can install and tap around.

- Package / applicationId: `com.fortress.trader`
- Min Android version: 8.0 (API 26)
- App name on device: **Plays**

## Easiest way to get the APK on your phone

This repo has a GitHub Actions workflow (`.github/workflows/plays-app-build.yml`)
that builds the APK on every push and attaches it to a pre-release named
**"Plays App (latest debug build)"** (tag `plays-app-latest`).

1. Open the repo's **Releases** page on your phone's browser.
2. Open **Plays App (latest debug build)**.
3. Download `app-debug.apk` and tap it to install.
4. If Android blocks it, allow **"Install unknown apps"** for your browser, then retry.

## Build it yourself (with Android Studio / SDK installed)

```bash
cd plays-app
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Or just open the `plays-app` folder in Android Studio and press Run.
