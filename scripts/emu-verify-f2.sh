#!/usr/bin/env bash
# Verifica E2E F2 (adapter Bybit + Kraken) — dopo il boot dell'emulatore.
# Copre: chips provider (Auto/Binance/Bybit/Kraken), switch su Bybit e Kraken
# con badge live e coin detail provider-aware, crash check finale.
# Coordinate 1080x2400 (pixel_6): chips ~y506, prima card ~(276,800).
set -u
export PATH=$PATH:/opt/android-sdk/platform-tools
REPO=/work/repo
SHOTS=$REPO/shots/f2
PKG=com.adgent.trader
mkdir -p "$SHOTS"
shot() { adb exec-out screencap -p > "$SHOTS/$1.png"; echo "shot $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml 2>/dev/null; }
FAIL=0
has() { if dump | grep -q "$1"; then echo "  ok: '$1'"; else echo "  MISSING: '$1'"; FAIL=1; fi; }
adb logcat -c

until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "boot ok"

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$REPO/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null

# Tap al centro del nodo uiautomator con text="$1" (robusto alle coordinate chips).
tapText() {
  local t="$1" xml node b x1 y1 x2 y2
  xml=$(dump)
  node=$(printf '%s' "$xml" | grep -oE '<node[^>]*text="'"$t"'"[^>]*>' | head -1)
  b=$(printf '%s' "$node" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | tail -1)
  if [ -z "$b" ]; then echo "  tapText: '$t' introvabile"; return 1; fi
  x1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
  y1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
  x2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
  y2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
  adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
  echo "  tapText '$t' @ $(( (x1+x2)/2 )),$(( (y1+y2)/2 ))"
}

# 1. Mercati: chips di tutti i provider F2 presenti
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 16
shot 01-markets-f2
echo "--- mercati: chips provider ---"
has "Markets"
has "Auto"
has "Binance"
has "Bybit"
has "Kraken"

# 2. Switch su Bybit: il ranking diventa Bybit (prima card → coin detail "· Bybit").
#    Il LiveBadge resta ancorato alla WATCHLIST (live solo lì): su install pulito
#    è "Live data · Binance" — il badge è un check di regressione, non di chip.
tapText "Bybit"; sleep 12
shot 02-markets-bybit
echo "--- Bybit ---"
has "Live data"
adb shell input tap 276 800; sleep 9
shot 03-coin-bybit
echo "--- coin detail Bybit ---"
has "Bybit"
adb shell input keyevent 4; sleep 3

# 3. Switch su Kraken: ranking + coin detail provider-aware
tapText "Kraken"; sleep 12
shot 04-markets-kraken
echo "--- Kraken ---"
has "Live data"
adb shell input tap 276 800; sleep 9
shot 05-coin-kraken
echo "--- coin detail Kraken ---"
has "Kraken"
adb shell input keyevent 4; sleep 3

# 4. Ritorno ad Auto e crash check
tapText "Auto"; sleep 6
echo "--- crash check ---"
if adb logcat -d | grep -q "FATAL EXCEPTION"; then echo "FATAL found"; FAIL=1; else echo "  no FATAL"; fi
if adb shell pidof "$PKG" >/dev/null; then echo "  app alive"; else echo "  APP MORTA"; FAIL=1; fi

if [ "$FAIL" = 0 ]; then echo "VERIFY OK"; else echo "VERIFY FAIL"; exit 1; fi
