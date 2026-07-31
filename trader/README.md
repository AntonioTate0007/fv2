# Fortress AI — Trader (`:trader`)

A standalone Jetpack Compose / Material 3 Android app: the **Fortress AI Options
Command Center**. Built out from the single-file demo into a properly structured
module with a theme layer, bottom-tab navigation, a small data layer, and one
file per screen.

## Run

This is a separate Gradle module in the same project. With the Android SDK set up
(an SDK path in `local.properties` or `ANDROID_HOME`):

```bash
./gradlew :trader:installDebug   # build + install on a connected device/emulator
./gradlew :trader:assembleDebug  # build the APK only
```

It does not depend on the existing `:app` module and ships nothing from it.

## Structure

```
com.fortress.trader
├── MainActivity.kt              # Scaffold + bottom-nav host
├── ui/theme/                    # Dark Fortress palette, typography, Material3 theme
├── ui/navigation/               # TraderDestination tabs
├── ui/components/FortressCard   # Shared rounded surface card
├── ui/screens/                  # Dashboard, Portfolio, Risk, Settings
└── data/                        # Position model + SampleData (swap for a repository)
```

The screens read from `data/SampleData.kt`. Replace that object with a repository
(network / DB) to make the app live.
