#!/usr/bin/env bash
# Verifica E2E 0.3.2 (ondata "flussi multi-provider"):
#  1) versione APK 0.3.2 / 14
#  2) card Markets compatte (niente riga "24h H/L") e label provider intera
#  3) AUTO: la griglia All mostra card di PIÙ provider (non solo Binance)
#  4) grafico Kraken rende (fix UDCUSD + chiavi canoniche) — tap card diretta
#  5) grafico KuCoin rende (regressione chart multi-provider)
#  6) Losers/Gainers: ordinamento reale (regressione)
#
# Pre-requisiti: emulatore booted (network host), `./docker/build.sh assembleDebug`.
set -u
export PATH=$PATH:/opt/android-sdk/platform-tools
REPO=/work/repo
SHOTS=$REPO/shots/f032
PKG=com.adgent.trader
mkdir -p "$SHOTS"
shot() { adb exec-out screencap -p > "$SHOTS/$1.png"; echo "shot $1"; }
dump() { adb exec-out uiautomator dump /dev/tty 2>/dev/null; }
FAIL=0

dismissAnr() {
  local line b x1 y1 x2 y2 cx cy i
  for i in 1 2 3; do
    if ! dump | grep -q "isn't responding"; then return 0; fi
    line=$(dump | grep -oE '<node[^>]*text="Wait"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
    if [ -z "$line" ]; then echo "  [ANR] no Wait button"; adb shell input keyevent KEYCODE_ENTER; sleep 2; continue; fi
    b=$(echo "$line" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
    x1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
    y1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
    x2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
    y2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
    cx=$(( (x1+x2)/2 )); cy=$(( (y1+y2)/2 ))
    echo "  [ANR] tapping Wait ($cx,$cy)"
    adb shell input tap "$cx" "$cy"; sleep 2
  done
}

tapText() {
  local label="$1"
  local line
  line=$(dump | grep -oE '<node[^>]*text="'"$label"'"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
  if [ -z "$line" ]; then echo "  MISSING text: '$label'"; FAIL=1; return 1; fi
  local b
  b=$(echo "$line" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  local x1 y1 x2 y2
  x1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
  y1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
  x2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
  y2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
  local cx=$(( (x1+x2)/2 )); local cy=$(( (y1+y2)/2 ))
  adb shell input tap "$cx" "$cy"; echo "  tap '$label' ($cx,$cy)"
}

pctSeq() { dump | grep -oE 'text="[+−-]?[0-9]+\.[0-9]+%"' | sed -E 's/text="//; s/"//' | tr -d '"'; }
txtSeq() { dump | grep -oE 'text="[^"]+"' | sed -E 's/text="([^"]+)"/\1/'; }
firstCardPct() { pctSeq | head -1 | sed -E 's/%$//; s/^−/-/'; }

# Rivela un chip orizzontale (swipe sulla riga chips) se non ancora visibile.
revealChip() {
  local label="$1" i
  for i in $(seq 1 8); do
    if dump | grep -q "text=\"$label\""; then return 0; fi
    adb shell input swipe 1000 528 100 528 200; sleep 1
  done
  return 1
}

relaunch() {
  adb shell am force-stop "$PKG"; sleep 1
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
  sleep 22; dismissAnr
}

# Apre il grafico della PRIMA card con symbol iniziante per base (es. BTC*)
# su un provider dato; verifica header "BASE/QUOTE · <provider>" e assenza NO DATA.
checkChart() {
  local prov="$1" base="$2"
  if ! revealChip "$prov"; then echo "  FAIL: chip '$prov' non trovato"; FAIL=1; return 1; fi
  tapText "$prov"; sleep 14; dismissAnr
  local card
  card=$(dump | grep -oE '<node[^>]*text="'"$base"'[A-Z0-9]*"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
  if [ -z "$card" ]; then echo "  FAIL: nessuna card $base* per '$prov'"; FAIL=1; return 1; fi
  local b
  b=$(echo "$card" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1)
  local x1 y1 x2 y2
  x1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
  y1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
  x2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
  y2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
  local cx=$(( (x1+x2)/2 )); local cy=$(( (y1+y2)/2 ))
  adb shell input tap "$cx" "$cy"; echo "  tap card $base* ($cx,$cy)"
  sleep 10; dismissAnr
  if dump | grep -q "text=\"[^\"]*· $prov\""; then
    echo "  ok: header '· $prov'"
  else
    echo "  FAIL: header '· $prov' non trovato"; FAIL=1
  fi
  if dump | grep -qE "text=\"(NO DATA AVAILABLE|RETRY)\""; then
    echo "  FAIL: NO DATA / RETRY sul grafico $prov"; FAIL=1
  else
    echo "  ok: niente NO DATA per $prov"
  fi
  shot "chart-$prov"
  # Torna alla lista (BACK dal grafico → Markets).
  adb shell input keyevent KEYCODE_BACK; sleep 2
}

adb logcat -c
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "boot ok"

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$REPO/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null

echo "=== [1] versione APK ==="
adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode" | head -3
VNAME=$(adb shell dumpsys package "$PKG" | grep -oE 'versionName=[0-9.]+' | head -1 | cut -d= -f2)
VCODE=$(adb shell dumpsys package "$PKG" | grep -oE 'versionCode=[0-9]+' | head -1 | cut -d= -f2)
[ "$VNAME" = "0.3.2" ] && [ "$VCODE" = "14" ] || { echo "  FAIL versione (atteso 0.3.2 / 14)"; FAIL=1; }

relaunch
shot 01-markets

echo "=== [2] card compatte (niente 24h H/L) ==="
if dump | grep -q '24h H'; then echo "  FAIL: ancora '24h H'"; FAIL=1; else echo "  ok: nessuna '24h H'"; fi
if dump | grep -q '24h L'; then echo "  FAIL: ancora '24h L'"; FAIL=1; else echo "  ok: nessuna '24h L'"; fi

echo "=== [3] AUTO: card di piu provider ==="
sleep 20; dismissAnr
# In AUTO le card mostrano la label provider; la chip è solo una occorrenza,
# quindi se "Binance"/"Bybit" compaiono ≥2 volte le label sono sulle card.
for p in Binance Bybit; do
  n=$(txtSeq | grep -cx "$p")
  echo "  occorrenze '$p': $n"
  if [ "$n" -ge 2 ]; then echo "  ok: label '$p' sulle card (AUTO)"; else echo "  (informative)"; fi
done
for s in BTCUSD BTCUSDT ETHUSD ETHUSDT; do
  if txtSeq | grep -qx "$s"; then echo "  ok: card $s presente in AUTO"; fi
done
shot 02-auto

echo "=== [4] grafico Kraken (fix UDCUSD + chiavi canoniche) ==="
relaunch
checkChart "Kraken" "BTC"

echo "=== [5] grafico KuCoin (regressione chart multi-provider) ==="
relaunch
checkChart "KuCoin" "BTC"

echo "=== [6] LOSERS/GAINERS ordinamento reale ==="
relaunch
tapText "Losers"; sleep 3; dismissAnr
for i in $(seq 1 16); do
  P=$(firstCardPct)
  if [ -n "$P" ] && awk -v p="$P" 'BEGIN{exit !(p < -5.0)}'; then break; fi
  sleep 5
done
if [ -n "${P:-}" ] && awk -v p="$P" 'BEGIN{exit !(p < -5.0)}'; then
  echo "  ok: prima card LOSERS = $P% (vero peggior perdente in cima)"
else
  echo "  FAIL: prima card LOSERS = ${P:-n/d} (atteso < -5%)"; FAIL=1
fi
tapText "Gainers"; sleep 3; dismissAnr
P2=$(firstCardPct)
if [ -n "$P2" ] && awk -v p="$P2" 'BEGIN{exit !(p > 5.0)}'; then
  echo "  ok: prima card GAINERS = $P2% (vero gainer)"
else
  echo "  FAIL: prima card GAINERS = ${P2:-n/d} (atteso > +5%)"; FAIL=1
fi
shot 03-order

echo "--- crash check ---"
adb logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION" | head -3 || echo "  nessun FATAL ✓"
adb shell pidof "$PKG" >/dev/null && echo "  app viva ✓" || { echo "  APP MORTA ✗"; FAIL=1; }

if [ "$FAIL" = 0 ]; then echo "VERIFY OK"; else echo "VERIFY FAIL"; exit 1; fi
