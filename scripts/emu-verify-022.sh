#!/usr/bin/env bash
# Verifica E2E 0.2.2 — versione interattiva completa, da eseguire subito dopo il boot.
export PATH=$PATH:/opt/android-sdk/platform-tools
REPO=/work/repo
SHOTS=$REPO/shots/022
PKG=com.adgent.trader
mkdir -p "$SHOTS"
shot() { adb exec-out screencap -p > "$SHOTS/$1.png"; echo "shot $1"; }
adb logcat -c

# Attendi boot
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "boot ok"

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$REPO/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null

# 1. Mercati (attendi caricamento dati ~15s)
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 16
shot 01-markets

# 2. Ricerca PEPE (icona 🔍 in alto a destra)
adb shell input tap 960 355; sleep 2
adb shell input text "PEPE"; sleep 5
shot 02-search-pepe
adb shell input keyevent 111; sleep 1   # chiudi ricerca

# 3. Ricerca TRX
adb shell input tap 960 355; sleep 2
adb shell input text "TRX"; sleep 5
shot 03-search-trx
adb shell input keyevent 111; sleep 1

# 4. Dettaglio coin (prima riga lista)
adb shell input tap 540 700; sleep 9
shot 04-chart

# 5. Back → Impostazioni
adb shell input keyevent 4; sleep 2
adb shell input tap 900 2330; sleep 3
shot 05-settings

# 6. Scroll giù fino alla sezione Backup & restore (i bottoni sono sotto la piega,
#    ~y>2127; dopo lo scroll: Export≈(257,1438), Restore≈(654,1438) — verificati col
#    picker che si apre). Tap su Export — non deve crashare e deve aprire il picker.
adb shell input swipe 540 1900 540 700 300; sleep 2
adb shell input swipe 540 1900 540 700 300; sleep 2
adb shell input tap 257 1438; sleep 4
shot 06-export-picker
adb shell input keyevent 4; sleep 2

# 7. Restore: tap sul bottone reale (stessa posizione scroll) — non deve crashare
adb shell input tap 654 1438; sleep 4
shot 07-restore-picker
adb shell input keyevent 4; sleep 2

# 8. Crash check
echo "--- crash check ---"
adb logcat -d 2>/dev/null | grep -E 'FATAL EXCEPTION' | head -3 || echo "nessun FATAL ✓"
adb shell pidof "$PKG" >/dev/null && echo "app viva ✓" || echo "APP MORTA ✗"
echo "VERIFY DONE"
