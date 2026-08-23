#!/usr/bin/env bash
# Verifica E2E 0.2.3 (UI card grid) — da eseguire subito dopo il boot dell'emulatore.
export PATH=$PATH:/opt/android-sdk/platform-tools
REPO=/work/repo
SHOTS=$REPO/shots/023
PKG=com.adgent.trader
mkdir -p "$SHOTS"
shot() { adb exec-out screencap -p > "$SHOTS/$1.png"; echo "shot $1"; }
adb logcat -c

until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "boot ok"

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$REPO/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null

# 1. Mercati: griglia di card 2-per-riga (attendi caricamento ~15s)
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 16
shot 01-markets-grid

# 2. Scroll della griglia per mostrare altre card
adb shell input swipe 540 1800 540 900 300; sleep 2
shot 02-markets-scrolled
adb shell input swipe 540 900 540 1800 300; sleep 2   # torna su

# 3. Ricerca PEPE (icona 🔍 in alto a destra)
adb shell input tap 960 355; sleep 2
adb shell input text "PEPE"; sleep 5
shot 03-search-pepe
adb shell input keyevent 111; sleep 1   # chiudi ricerca

# 4. Dettaglio coin: tap sulla prima card della griglia (≈(276,506))
adb shell input tap 276 506; sleep 9
shot 04-chart

# 5. Back → tab Avvisi (centro della bottom nav)
adb shell input keyevent 4; sleep 2
adb shell input tap 540 2330; sleep 3
shot 05-alerts

# 6. Tab Impostazioni
adb shell input tap 900 2330; sleep 3
shot 06-settings

# 7. Crash check
echo "--- crash check ---"
adb logcat -d 2>/dev/null | grep -E 'FATAL EXCEPTION' | head -3 || echo "nessun FATAL ✓"
adb shell pidof "$PKG" >/dev/null && echo "app viva ✓" || echo "APP MORTA ✗"
echo "VERIFY DONE"
