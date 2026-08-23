#!/usr/bin/env bash
# Diagnosi Export SAF 0.2.2 — perché il picker non si apre (05==06 byte-identici).
export PATH=$PATH:/opt/android-sdk/platform-tools
REPO=/work/repo
SHOTS=$REPO/shots/022diag
PKG=com.adgent.trader
mkdir -p "$SHOTS"
shot() { adb exec-out screencap -p > "$SHOTS/$1.png"; echo "shot $1"; }
adb logcat -c

until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "boot ok"

echo "=== A. DocumentsUI presente? ==="
adb shell pm list packages | grep -i doc || echo "NESSUN package docs"

echo "=== B. TEST DIRETTO ACTION_CREATE_DOCUMENT (bypassa l'app) ==="
adb shell am start -a android.intent.action.CREATE_DOCUMENT \
  --es android.intent.extra.TITLE "test-export.json" -t application/json 2>&1 | head -3
sleep 3
shot 10-create-doc-direct
adb shell input keyevent 4; sleep 2

echo "=== C. TEST DIRETTO ACTION_OPEN_DOCUMENT (confronto) ==="
adb shell am start -a android.intent.action.OPEN_DOCUMENT -t application/json 2>&1 | head -3
sleep 3
shot 10b-open-doc-direct
adb shell input keyevent 4; sleep 2

echo "=== D. App: tap Export col logcat catturato ==="
adb install -r "$REPO/app/build/outputs/apk/debug/app-debug.apk" 2>&1 | tail -1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 8
adb shell input tap 900 2330; sleep 3
adb logcat -c
adb shell input tap 300 1560; sleep 2
adb logcat -d > "$SHOTS/logcat-export.txt"
shot 11-export-t0
sleep 2
shot 12-export-t2
echo "--- logcat export (filtrato) ---"
grep -Ei 'Exception|DocumentsUI|ActivityNotFound|CREATE_DOCUMENT|adgent|AndroidRuntime|ActivityTaskManager.*START' \
  "$SHOTS/logcat-export.txt" | head -40 || true
echo "EXPORT DIAG DONE"
