#!/usr/bin/env bash
# Installs the release APK on the emulator, opens the app the way a user would,
# and saves logcat + screenshots so crashes can be read from CI.
set -x
PKG=com.thumbshade.app
OUT=smoke
mkdir -p "$OUT"

adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -c

shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }

# 1. Fresh launch with no permissions, like right after installing.
adb shell am start -W -n $PKG/.ui.MainActivity
sleep 8
shot 1-first-launch

# 2. Grant what the user grants on the General tab, then relaunch.
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell cmd notification allow_listener $PKG/$PKG.notif.ShadeListenerService
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.ui.MainActivity
sleep 12
shot 2-with-permissions

# 3. A notification arrives, then the shade opens.
adb shell cmd notification post -S bigtext -t "Smoke test" smoke1 "Your verification code is 482913"
sleep 4
adb shell am start -n $PKG/.ui.ShowShadeActivity
sleep 6
shot 3-shade-open
adb shell input keyevent KEYCODE_BACK
sleep 2

# 4. Visit every settings tab (bottom bar, 5 equal slots).
adb shell am start -W -n $PKG/.ui.MainActivity
sleep 3
read -r W H < <(adb shell wm size | awk -F'[ x]' '/Physical/{print $3, $4}')
Y=$((H - H / 30))
for i in 0 1 2 3 4; do
  X=$((W * (2 * i + 1) / 10))
  adb shell input tap "$X" "$Y"
  sleep 3
  shot "4-tab-$i"
done

adb shell dumpsys activity services $PKG > "$OUT/services.txt" || true
adb logcat -d > "$OUT/logcat.txt"
adb logcat -d -b crash > "$OUT/crash.txt" || true

echo "===== CRASHES ====="
grep -n -A60 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -300 || true
cat "$OUT/crash.txt" | head -200
echo "===== APP LOG ====="
grep -E "ThumbShade|com\.thumbshade" "$OUT/logcat.txt" | grep -vE "ActivityTaskManager: (START|Displayed)" | head -80

if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "App crashed"
  exit 1
fi
exit 0
