#!/usr/bin/env bash
# Verifica E2E F1 (port multi-provider, parità 0.2.8) — da eseguire dopo il boot
# dell'emulatore. Copre i flussi chiave: mercati (chips provider + live badge),
# coin detail provider-aware, nuovi alert + edit + alert da coin detail, settings.
#
# Coordinate su 1080x2400 (pixel_6): la griglia parte sotto la riga chips
# (prima card ~y800, NON y506 che è la chip Auto), bottom nav y≈2274.
set -u
export PATH=$PATH:/opt/android-sdk/platform-tools
REPO=/work/repo
SHOTS=$REPO/shots/f1
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

MARKET_TAB="173 2274"; ALERT_TAB="540 2274"; SETTINGS_TAB="906 2274"

# 1. Mercati: header, badge live, chips provider, filtri
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 16
shot 01-markets-grid
echo "--- markets ---"
has "Markets"
has "Live data"
has "Auto"
has "Binance"
has "Gainers"
has "Favorites"

# 2. Coin detail: tap prima card → header provider-aware + Source (le stats sono
#    sotto la piega del grafico: si scrolla prima di verificarle)
adb shell input tap 276 800; sleep 10
shot 02-coin-detail
echo "--- coin detail ---"
has "Source"
adb shell input swipe 540 1500 540 800 400; sleep 2
has "24h statistics"
adb shell input swipe 540 800 540 2000 400; sleep 1

# 3. Nuovo alert da Alerts (rotta alertEdit): back → tab Alerts → FAB
#    (la presenza del FAB è provata dal fatto che il tap sotto apre il form)
adb shell input keyevent 4; sleep 2
adb shell input tap $ALERT_TAB; sleep 4
shot 03-alerts
echo "--- alerts ---"
has "Alerts"
adb shell input tap 540 1470; sleep 4
shot 04-alert-new
echo "--- alert form (nuovo) ---"
has "New alert"
has "Instrument"
has "Threshold price"
has "When to alert me"
has "Save alert"

# 4. Edit alert: salva una regola con soglia, poi riaperti dall'elenco
adb shell input tap 540 1293; sleep 2
adb shell input text 50000; sleep 1
adb shell input keyevent 111; sleep 1
adb shell input tap 540 1797; sleep 4
echo "--- alert salvato ---"
has "above 50,000"
has "Edit"
# tap Edit sulla riga appena creata
adb shell input tap 452 603; sleep 4
shot 05-alert-edit
echo "--- alert form (edit) ---"
has "Edit alert"
has "Delete"

# 5. Settings: indietro → tab Settings → sezione sorgente dati (in fondo) e
#    sezioni in cima (Appearance/Price alerts) con scroll in entrambe le direzioni
adb shell input keyevent 4; sleep 2
adb shell input tap $SETTINGS_TAB; sleep 4
adb shell input swipe 540 2000 540 800 500; sleep 2
shot 06-settings
echo "--- settings (sorgente dati) ---"
has "Market data source"
has "Auto"
has "Binance"
has "Backup"
adb shell input swipe 540 800 540 2000 500; sleep 2
echo "--- settings (in alto) ---"
has "Appearance"
has "Price alerts"

# 6. Crash check finale
echo "--- crash check ---"
adb logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION" | head -3 || echo "  nessun FATAL ✓"
adb shell pidof "$PKG" >/dev/null && echo "  app viva ✓" || { echo "  APP MORTA ✗"; FAIL=1; }

if [ "$FAIL" = 0 ]; then echo "VERIFY OK"; else echo "VERIFY FAIL"; exit 1; fi
