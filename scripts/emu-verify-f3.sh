#!/usr/bin/env bash
# Verifica E2E F3 (adapter Coinbase + OKX + Bitfinex + KuCoin) — dopo il boot.
# Le chip sono in una LazyRow: l'inizio (Auto) è a sinistra, la fine (KuCoin)
# a destra. Swipe a SINISTRA rivela la fine; swipe a DESTRA torna all'inizio.
# Per ogni provider: reset riga → selezione chip → ranking carica (poll) → tap
# sul testo esatto della prima card (da un singolo dump) → coin detail
# provider-aware ("SYM · Provider"). Back dal coin detail SOLO se si è aperto
# (mai uscire dal root Markets). Crash check filtrato al solo processo app.
set -u
export PATH=$PATH:/opt/android-sdk/platform-tools
REPO=/work/repo
SHOTS=$REPO/shots/f3
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

# uiautomator dump è racy: retry interni per tollerare dump vuoti/stantii.
centerOf() { # $1=testo → stampa "x y" se trovato (una sola dump), altrimenti vuoto
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
nodeXY() { # con retry: uiautomator può restituire un dump senza il nodo per un attimo
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
# Inizio della LazyRow = a sinistra: swipe a DESTRA (x crescente) per tornare ad Auto.
chipsHome() { for _ in $(seq 1 8); do adb shell input swipe 200 528 950 528 200; sleep 0.5; done; }
findChip() { # scrolla verso la fine della riga finché il text non è visibile
  local t="$1" n
  for n in $(seq 1 8); do
    [ -n "$(nodeXY "$t")" ] && return 0
    adb shell input swipe 900 528 250 528 250; sleep 0.5
  done
  echo "  findChip: '$t' non raggiungibile"; return 1
}
# Poll (max ~30s) finché una card top coin non è visibile.
waitTopCoin() {
  local n cand
  for n in $(seq 1 10); do
    for cand in BTCUSDT BTCUSD BTCUSDC ETHUSDT ETHUSD XBTUSD; do
      [ -n "$(nodeXY "$cand")" ] && { echo "  ranking: card '$cand' visibile"; return 0; }
    done
    sleep 3
  done
  echo "  ranking non caricato entro ~30s"; return 1
}
# Tap sulla prima card top coin presente in UN SOLO dump (niente dump multipli racy).
tapTopCoin() {
  local xml cand xy
  for _ in 1 2 3; do
    xml=$(dump)
    for cand in BTCUSDT BTCUSD BTCUSDC ETHUSDT ETHUSD XBTUSD; do
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
  echo "  tapTopCoin: nessuna card trovata"; return 1
}
checkProvider() { # $1=chip, $2=nome provider
  local chip="$1" name="$2"
  chipsHome; sleep 1
  findChip "$chip" || { FAIL=1; return; }
  sleep 0.5
  tapText "$chip" || { FAIL=1; return; }
  waitTopCoin || { FAIL=1; return; }
  shot "mk-$name"
  echo "--- $name ---"
  tapTopCoin || { FAIL=1; return; }
  sleep 9
  shot "coin-$name"
  if has "Source" && has "· $name"; then
    echo "  coin detail '$name' OK"
    adb shell input keyevent 4; sleep 3
  else
    FAIL=1
    echo "  [checkProvider] coin detail '$name' non verificato"
    # back SOLO se il coin detail si è aperto (altrimenti usciremmo dall'app)
    if has "Source"; then adb shell input keyevent 4; sleep 3; fi
  fi
}

adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 16
shot 01-markets-f3
echo "--- mercati ---"
has "Markets"
has "Auto"
has "Binance"

checkProvider "Coinbase" "Coinbase"
checkProvider "OKX" "OKX"
checkProvider "Bitfinex" "Bitfinex"
checkProvider "KuCoin" "KuCoin"

chipsHome
tapText "Auto"; sleep 6
echo "--- crash check ---"
# FATAL solo se nel processo dell'app (uiautomator può crashare di suo, non conta)
if adb logcat -d | grep -A3 "FATAL EXCEPTION" | grep -q "$PKG"; then
  echo "FATAL found"; FAIL=1
else
  echo "  no FATAL (app)"
fi
if adb shell pidof "$PKG" >/dev/null; then echo "  app alive"; else echo "  APP MORTA"; FAIL=1; fi

if [ "$FAIL" = 0 ]; then echo "VERIFY OK"; else echo "VERIFY FAIL"; exit 1; fi
