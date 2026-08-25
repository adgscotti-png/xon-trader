#!/usr/bin/env bash
# Verifica E2E 0.3.1 (ondata UI Andrea):
#  1) card Markets senza riga "24h H / 24h L / Vol" (card più corte)
#  2) timeframe M5 presente + default grafico = 15m
#  3) tab Gainers/Losers: ORDINE REALE mostrato con mercato pieno —
#     i veri peggiori (LOSERS) in cima, i veri migliori (GAINERS) in cima.
#     (Regressione "LOSERS parte dal basso": era l'anchor scroll della
#      LazyGrid quando la cache cresce da parziale a mercato pieno.)
#
# Pre-requisiti: emulatore booted (network host), `./docker/build.sh assembleDebug`.
set -u
export PATH=$PATH:/opt/android-sdk/platform-tools
REPO=/work/repo
SHOTS=$REPO/shots/f031
PKG=com.adgent.trader
mkdir -p "$SHOTS"
shot() { adb exec-out screencap -p > "$SHOTS/$1.png"; echo "shot $1"; }
dump() { adb exec-out uiautomator dump /dev/tty 2>/dev/null; }
FAIL=0

dismissAnr() {
  for i in 1 2 3; do
    if dump | grep -q "isn't responding"; then
      echo "  [ANR] tapping Wait"; adb shell input tap 700 1440 2>/dev/null; sleep 2
    fi
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

# Sequenza % in ordine documento (U+2212 = minus Unicode usato da Format.percent)
pctSeq() { dump | grep -oE 'text="[+−-]?[0-9]+\.[0-9]+%"' | sed -E 's/text="//; s/"//' | tr -d '"'; }

# Sequenza completa testi visibili (per leggere nome+% delle card)
txtSeq() { dump | grep -oE 'text="[^"]+"' | sed -E 's/text="([^"]+)"/\1/'; }

# % della PRIMA card visibile (numero puro, es. -22.35)
firstCardPct() {
  pctSeq | head -1 | sed -E 's/%$//; s/^−/-/'
}

# Check che i % siano monotoni: ascending (LOSERS) o descending (GAINERS),
# confronto NUMERICO (normalizza U+2212 → '-', toglie '%').
checkMono() {
  local label="$1" mode="$2"
  pctSeq | head -14 | sed -E 's/%$//; s/^−/-/' > "$SHOTS/$label-pct.txt"
  local prev="" ok=1 v
  while IFS= read -r v; do
    [ -z "$v" ] && continue
    if [ -n "$prev" ]; then
      if [ "$mode" = asc ]; then
        if awk -v a="$prev" -v b="$v" 'BEGIN{exit !(b < a - 0.0005)}'; then ok=0; echo "  BROKEN asc: $prev then $v"; fi
      else
        if awk -v a="$prev" -v b="$v" 'BEGIN{exit !(b > a + 0.0005)}'; then ok=0; echo "  BROKEN desc: $prev then $v"; fi
      fi
    fi
    prev="$v"
  done < "$SHOTS/$label-pct.txt"
  if [ "$ok" = 1 ]; then echo "  ok: $label ordinamento $mode monotono"; else FAIL=1; fi
}

# Attende che il mercato si riempia: la prima card LOSERS deve essere un VERO
# grosso perdente (% < -5). Distingue: cache parziale (solo watchlist ~ -3%),
# bug anchor scroll (card ~ -3% a metà lista) e stablecoin (~0%) dal mercato
# pieno dove il peggior perdente del giorno è in cima (tipicamente -10/-40%).
waitRealLoser() {
  local i p
  for i in $(seq 1 16); do
    p=$(firstCardPct)
    if [ -n "$p" ] && awk -v p="$p" 'BEGIN{exit !(p < -5.0)}'; then
      echo "  mercato pieno dopo ~$((i*5))s (prima card LOSERS = $p%)"
      return 0
    fi
    sleep 5
  done
  echo "  TIMEOUT: prima card LOSERS = ${p:-n/d} (atteso < -5%: vero peggior perdente in cima)"; FAIL=1
  return 1
}

adb logcat -c
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "boot ok"

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$REPO/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null

adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 20
dismissAnr
shot 01-markets

echo "=== [1] card senza riga 24h ==="
if dump | grep -q '24h H'; then echo "  FAIL: ancora '24h H' in pagina"; FAIL=1; else echo "  ok: nessuna '24h H'"; fi
if dump | grep -q '24h L'; then echo "  FAIL: ancora '24h L' in pagina"; FAIL=1; else echo "  ok: nessuna '24h L'"; fi

echo "=== [3a] LOSERS — ordine reale con mercato pieno ==="
tapText "Losers"; sleep 3; dismissAnr
waitRealLoser
shot 02-losers
txtSeq | head -24 > "$SHOTS/02-losers-cards.txt"
echo "--- prime card (nome, simbolo, prezzo, %) ---"
cat "$SHOTS/02-losers-cards.txt"
checkMono losers asc

echo "=== [3b] GAINERS — ordine reale con mercato pieno ==="
tapText "Gainers"; sleep 3; dismissAnr
# Il mercato è già pieno: la prima card GAINERS deve essere un vero gainer (% > +1.5)
local_gainer_check() {
  local p; p=$(firstCardPct)
  if [ -n "$p" ] && awk -v p="$p" 'BEGIN{exit !(p > 5.0)}'; then
    echo "  ok: prima card GAINERS = $p% (vero gainer)"
  else
    echo "  FAIL: prima card GAINERS = ${p:-n/d} (atteso > +5%)"; FAIL=1
  fi
}
local_gainer_check
shot 03-gainers
txtSeq | head -24 > "$SHOTS/03-gainers-cards.txt"
echo "--- prime card (nome, simbolo, prezzo, %) ---"
cat "$SHOTS/03-gainers-cards.txt"
checkMono gainers desc

echo "=== [2] grafico: default 15m + chip 5m ==="
tapText "All"; sleep 2
adb shell input tap 276 800; sleep 10
dismissAnr
shot 04-chart
echo "--- chips timeframe ---"
txtSeq | grep -E '^(1m|5m|15m|1h|4h|1d|1w|1M)$'
echo "--- selected chip (in ordine documento) ---"
dump | grep -oE '<node[^>]*text="[^"]*"[^>]*selected="true"' | grep -oE 'text="[^"]+"' | sed -E 's/text="([^"]+)"/\1/' | head -5

echo "--- crash check ---"
adb logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION" | head -3 || echo "  nessun FATAL ✓"
adb shell pidof "$PKG" >/dev/null && echo "  app viva ✓" || { echo "  APP MORTA ✗"; FAIL=1; }

if [ "$FAIL" = 0 ]; then echo "VERIFY OK"; else echo "VERIFY FAIL"; exit 1; fi
