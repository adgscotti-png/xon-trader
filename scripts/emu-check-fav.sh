#!/usr/bin/env bash
# Verifica interazioni preferito sulle nuove card (griglia + ricerca).
export PATH=$PATH:/opt/android-sdk/platform-tools
SHOTS=/work/repo/shots/023
PKG=com.adgent.trader
shot() { adb exec-out screencap -p > "$SHOTS/$1.png"; echo "shot $1"; }

adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 8

# 1. Long-press sulla prima card della griglia (≈(276,506)) → toggle preferito.
adb shell input swipe 276 506 276 506 700; sleep 2
shot 08-longpress-fav

# 2. La card ora mostra la stella dorata → long-press di nuovo per togliere.
adb shell input swipe 276 506 276 506 700; sleep 2
shot 09-longpress-unfav

# 3. Ricerca PEPE → tap sulla stella in alto a destra della card (toggle esplicito).
adb shell input tap 960 355; sleep 2
adb shell input text "PEPE"; sleep 5
shot 10-search-star-before
# Stella in alto a destra della prima card risultato (PEPE) ≈ (480,340)
adb shell input tap 480 340; sleep 2
shot 11-search-star-after
adb shell input keyevent 111; sleep 1

echo "--- crash check ---"
adb logcat -d 2>/dev/null | grep -E 'FATAL EXCEPTION' | head -3 || echo "nessun FATAL ✓"
adb shell pidof "$PKG" >/dev/null && echo "app viva ✓" || echo "APP MORTA ✗"
echo "CHECK DONE"
