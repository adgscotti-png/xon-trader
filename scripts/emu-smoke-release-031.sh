#!/usr/bin/env bash
# Smoke test APK RELEASE 0.3.1 (versionName/code + regression LOSERS).
# Esegue da un container builder con --network host contro l'emulatore xon-emu.
set -u
export PATH=$PATH:/opt/android-sdk/platform-tools
REPO=/work/repo
PKG=com.adgent.trader
FAIL=0

dump() { adb exec-out uiautomator dump /dev/tty 2>/dev/null; }
pctSeq() { dump | grep -oE 'text="[+−-]?[0-9]+\.[0-9]+%"' | sed -E 's/text="//; s/"//' | tr -d '"'; }
firstCardPct() { pctSeq | head -1 | sed -E 's/%$//; s/^−/-/'; }
tapText() {
  local label="$1" line b x1 y1 x2 y2 cx cy
  line=$(dump | grep -oE '<node[^>]*text="'"$label"'"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
  [ -z "$line" ] && { echo "  MISSING text: '$label'"; FAIL=1; return 1; }
  b=$(echo "$line" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  x1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
  y1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
  x2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
  y2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
  cx=$(( (x1+x2)/2 )); cy=$(( (y1+y2)/2 ))
  adb shell input tap "$cx" "$cy"; echo "  tap '$label' ($cx,$cy)"
}

until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "boot ok"

echo "=== versione APK ==="
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$REPO/app/build/outputs/apk/release/app-release.apk" | tail -1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode" | head -3
VNAME=$(adb shell dumpsys package "$PKG" | grep -oE 'versionName=[0-9.]+' | head -1 | cut -d= -f2)
VCODE=$(adb shell dumpsys package "$PKG" | grep -oE 'versionCode=[0-9]+' | head -1 | cut -d= -f2)
echo "  -> versionName=$VNAME versionCode=$VCODE"
[ "$VNAME" = "0.3.1" ] && [ "$VCODE" = "13" ] || { echo "  FAIL versione (atteso 0.3.1 / 13)"; FAIL=1; }

echo "=== avvio app ==="
adb logcat -c
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 20
echo "=== card compatte: nessuna 24h H/L ==="
if dump | grep -q '24h H'; then echo "  FAIL: ancora '24h H'"; FAIL=1; else echo "  ok: nessuna '24h H'"; fi
if dump | grep -q '24h L'; then echo "  FAIL: ancora '24h L'"; FAIL=1; else echo "  ok: nessuna '24h L'"; fi

echo "=== LOSERS: vero peggior perdente in cima ==="
tapText "Losers"
P=""
for i in $(seq 1 16); do
  P=$(firstCardPct)
  if [ -n "$P" ] && awk -v p="$P" 'BEGIN{exit !(p < -5.0)}'; then echo "  mercato pieno dopo ~$((i*5))s: prima card LOSERS = $P%"; break; fi
  sleep 5
done
if [ -n "$P" ] && awk -v p="$P" 'BEGIN{exit !(p < -5.0)}'; then
  echo "  ok: prima card LOSERS = $P% (vero peggior perdente in cima)"
else
  echo "  FAIL: prima card LOSERS = ${P:-n/d} (atteso < -5%)"; FAIL=1
fi

echo "=== app viva ==="
adb shell pidof "$PKG" >/dev/null && echo "  app viva ✓" || { echo "  APP MORTA ✗"; FAIL=1; }
adb logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION" | head -3 || echo "  nessun FATAL ✓"

[ "$FAIL" = 0 ] && echo "SMOKE RELEASE OK" || { echo "SMOKE RELEASE FAIL"; exit 1; }
