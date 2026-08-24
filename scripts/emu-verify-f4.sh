#!/usr/bin/env bash
# Verifica E2E F4 (UI multi-provider) — dopo il boot.
# 1) chips Auto+7 su Mercati · 2) coin detail: picker Source per-coin
# (Binance→Bybit, header cambia + override salvato) · 3) Favorites: il badge
# provider segue l'override (riga BTCUSDT → Bybit) · 4) Settings "Market data
# source": 8 chip presenti + selezione default · 5) crash check (solo app).
set -u
export PATH=$PATH:/opt/android-sdk/platform-tools
REPO=/work/repo
SHOTS=$REPO/shots/f4
PKG=com.adgent.trader
mkdir -p "$SHOTS"
shot() { adb exec-out screencap -p > "$SHOTS/$1.png"; echo "shot $1"; }
# timeout: un dump appeso non deve bloccare l'intero run.
# dump() scrive l'XML su stdout in UNA sola adb call (/dev/tty): la variante
# dump-su-file + cat a due fasi è fragile sotto carico host (il cat può fallire
# o tornare vuoto) e produce MISSING fantasma.
dump() { timeout 10 adb exec-out uiautomator dump /dev/tty 2>/dev/null; }
FAIL=0
has() { if dump | grep -q "$1"; then echo "  ok: '$1'"; else echo "  MISSING: '$1'"; FAIL=1; fi; }
adb logcat -c

until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "boot ok"

# Chiude eventuali ANR di sistema ("Process ... isn't responding") che sotto carico
# host coprono la UI: li scarta tappando Wait finché la home non è pulita.
dismissAnr() {
  local xml b x1 y1 x2 y2 n
  for n in 1 2 3 4; do
    xml=$(dump)
    if printf '%s' "$xml" | grep -q "isn't responding"; then
      b=$(printf '%s' "$xml" | grep -oE '<node[^>]*text="Wait"[^>]*>' | head -1 \
        | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | tail -1)
      [ -z "$b" ] && return 1
      x1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
      y1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
      x2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
      y2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
      adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); sleep 2
      echo "  [dismissAnr] Wait tappato"
    else
      return 0
    fi
  done
  return 1
}

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$REPO/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null

centerOf() {
  local t="$1" xml node b
  xml=$(dump)
  node=$(printf '%s' "$xml" | grep -oE '<node[^>]*text="'"$t"'"[^>]*>' | head -1)
  b=$(printf '%s' "$node" | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | tail -1)
  [ -z "$b" ] && return 1
  local x1 y1 x2 y2
  x1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
  y1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
  x2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
  y2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
  echo "$(( (x1+x2)/2 )) $(( (y1+y2)/2 ))"
}
nodeXY() {
  local t="$1" n xy
  for n in 1 2 3; do
    xy=$(centerOf "$t")
    [ -n "$xy" ] && { echo "$xy"; return 0; }
    sleep 0.3
  done
  echo ""; return 1
}
tapText() {
  local t="$1" xy
  xy=$(nodeXY "$t")
  [ -z "$xy" ] && { echo "  tapText: '$t' introvabile"; return 1; }
  adb shell input tap $xy; echo "  tapText '$t' @ $xy"
}
chipsHome() { for _ in $(seq 1 8); do adb shell input swipe 200 528 950 528 200; sleep 0.5; done; }
findChip() {
  local t="$1" n
  for n in $(seq 1 8); do
    [ -n "$(nodeXY "$t")" ] && return 0
    adb shell input swipe 900 528 250 528 250; sleep 0.5
  done
  echo "  findChip: '$t' non raggiungibile"; return 1
}
tapFirstCard() { # tap sulla prima card top coin (testo esatto, un solo dump)
  local xml cand xy
  for _ in 1 2 3; do
    xml=$(dump)
    for cand in BTCUSDT BTCUSD BTCUSDC ETHUSDT ETHUSD; do
      xy=$(printf '%s' "$xml" | grep -oE '<node[^>]*text="'"$cand"'"[^>]*>' | head -1 \
        | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | tail -1 \
        | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')
      if [ -n "$xy" ]; then
        set -- $xy
        local cx=$(( ($1+$3)/2 )) cy=$(( ($2+$4)/2 ))
        adb shell input tap $cx $cy; echo "  tap card '$cand' @ $cx $cy"
        return 0
      fi
    done
    sleep 0.5
  done
  echo "  tapFirstCard: nessuna card"; return 1
}
# back dal coin detail SOLO se si è aperto (mai uscire dal root Markets)
backFromCoin() {
  sleep 1
  if has "Source"; then adb shell input keyevent 4; sleep 3; else echo "  [warn] no Source, back saltato"; fi
}

dismissAnr || echo "  [warn] ANR non chiuso prima dello start"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
# Cold start lento (DB + bootstrap provider): poll fino a ~60s invece di un sleep fisso.
for _ in $(seq 1 12); do
  [ -n "$(nodeXY "Markets")" ] && break
  sleep 5
done
shot 01-markets-f4
echo "--- 1) mercati: chips ---"
has "Markets"
has "Auto"
has "Binance"
has "Bybit"
findChip "KuCoin" && echo "  chip KuCoin raggiungibile" || FAIL=1
chipsHome

echo "--- 2) coin detail: picker Source per-coin ---"
tapFirstCard || { FAIL=1; exit 1; }
sleep 9
shot 02-coin-binance
has "Source" || { FAIL=1; echo "  [abort] coin detail non aperto"; exit 1; }
has "· Binance" || FAIL=1
echo "  tap chip Source 'Bybit'"
tapText "Bybit" || { FAIL=1; backFromCoin; }
sleep 9
shot 03-coin-bybit
has "· Bybit" && echo "  override per-coin → '· Bybit' OK" || { echo "  MISSING '· Bybit'"; FAIL=1; }
backFromCoin

echo "--- 3) Favorites: badge provider segue override ---"
tapText "Favorites" || { FAIL=1; }
sleep 6
shot 04-favorites
has "Remove from favorites" || echo "  [warn] righe favorite non confermate via contentDescription"
tapFirstCard || { FAIL=1; }
sleep 9
shot 05-coin-fav-bybit
has "· Bybit" && echo "  riga favorite BTCUSDT risolve → Bybit OK" || { echo "  MISSING '· Bybit' su favorite"; FAIL=1; }
backFromCoin

echo "--- 4) Settings: Market data source ---"
tapText "Settings" || { FAIL=1; }
sleep 6
shot 06-settings
has "Market data source" || FAIL=1
# La riga chip impostazioni è una LazyRow orizzontale sotto la sezione: calcola
# il centro y della prima chip visibile e scrolla a quel y (niente y hardcoded).
sy=$(dump | grep -oE '<node[^>]*text="(Auto|Binance|Bybit|Kraken)"[^>]*>' | head -1 \
  | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | tail -1 \
  | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2 \4/' \
  | awk '{print int(($1+$2)/2)}')
if [ -z "$sy" ]; then echo "  [warn] riga chip settings introvabile"; FAIL=1; sy=800; fi
for p in Auto Binance Bybit Kraken Coinbase OKX Bitfinex KuCoin; do
  found=""
  for n in $(seq 1 8); do
    [ -n "$(nodeXY "$p")" ] && { found=1; break; }
    adb shell input swipe 900 "$sy" 250 "$sy" 250; sleep 0.5
  done
  if [ -n "$found" ]; then echo "  ok: chip '$p'"; else echo "  MISSING: chip '$p'"; FAIL=1; fi
done
# Torna all'inizio della riga e seleziona OKX come default.
for _ in $(seq 1 8); do adb shell input swipe 200 "$sy" 950 "$sy" 200; sleep 0.5; done
findOKX=""
for n in $(seq 1 8); do
  [ -n "$(nodeXY "OKX")" ] && { findOKX=1; break; }
  adb shell input swipe 900 "$sy" 250 "$sy" 250; sleep 0.5
done
[ -n "$findOKX" ] && tapText "OKX" || echo "  [warn] OKX non tappabile in settings"
sleep 3
shot 07-settings-okx

echo "--- 5) crash check ---"
if adb logcat -d | grep -A3 "FATAL EXCEPTION" | grep -q "$PKG"; then
  echo "FATAL found"; FAIL=1
else
  echo "  no FATAL (app)"
fi
if adb shell pidof "$PKG" >/dev/null; then echo "  app alive"; else echo "  APP MORTA"; FAIL=1; fi

if [ "$FAIL" = 0 ]; then echo "VERIFY OK"; else echo "VERIFY FAIL"; exit 1; fi
