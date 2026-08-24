#!/usr/bin/env bash
# Verifica E2E F5 (batteria multi-provider) — dopo il boot.
# SAVER (default): all'avvio il WS della watchlist si collega (logcat XONWs ...
# open); al background si CHIUDE (disconnect); al refocus si RIAPRE (connect).
# REALTIME: attivata da Settings → il foreground service tiene aperto il WS:
# al background NESSUN nuovo disconnect e nessun reconnect.
set -u
export PATH=$PATH:/opt/android-sdk/platform-tools
REPO=/work/repo
SHOTS=$REPO/shots/f5
PKG=com.adgent.trader
mkdir -p "$SHOTS"
shot() { adb exec-out screencap -p > "$SHOTS/$1.png"; echo "shot $1"; }
# Una sola adb call (/dev/tty): la variante dump-su-file + cat è fragile sotto carico.
dump() { timeout 10 adb exec-out uiautomator dump /dev/tty 2>/dev/null; }
# Fallback a coordinate fisse per la tab Settings se il dump fallisce (tab in alto a destra).
settingsTab() {
  local xy n
  for n in 1 2 3; do
    xy=$(nodeXY "Settings")
    [ -n "$xy" ] && { echo "tap Settings (dump) @ $xy"; adb shell input tap $xy; return 0; }
    sleep 0.4
  done
  echo "  [warn] dump Settings fallito, uso coordinate fisse (900,175)"
  adb shell input tap 900 175
  sleep 2
  if adb shell uiautomator dump /sdcard/chk.xml >/dev/null 2>&1 && adb exec-out cat /sdcard/chk.xml 2>/dev/null | grep -q "Price alerts"; then
    return 0
  fi
  return 1
}
FAIL=0
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
# Contatori sul buffer logcat (azzerato all'inizio).
openCnt()   { adb logcat -d | grep "XONWs" | grep -c " open"; }
discCnt()   { adb logcat -d | grep "XONWs" | grep -c "disconnect"; }
failCnt()   { adb logcat -d | grep "XONWs" | grep -c "failure"; }

adb logcat -c

until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
echo "boot ok"

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$REPO/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null

echo "--- SAVER (default) ---"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 20
shot saver-01-foreground
o1=$(openCnt); d1=$(discCnt)
echo "  open=$o1 disconnect=$d1 dopo start"
if [ "$o1" -lt 1 ]; then echo "  FAIL: nessun WS aperto in foreground"; FAIL=1; fi

adb shell input keyevent 3; sleep 10   # HOME → ci si aspetta la chiusura
shot saver-02-background
o2=$(openCnt); d2=$(discCnt)
echo "  open=$o2 disconnect=$d2 dopo background"
if [ "$d2" -le "$d1" ]; then echo "  FAIL: SAVER background → nessun disconnect"; FAIL=1; fi
if [ "$o2" -gt "$o1" ]; then echo "  FAIL: reconnect spurio in background SAVER"; FAIL=1; fi

adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 12   # refocus → riapertura
shot saver-03-refocus
o3=$(openCnt); d3=$(discCnt)
echo "  open=$o3 disconnect=$d3 dopo refocus"
if [ "$o3" -le "$o2" ]; then echo "  FAIL: SAVER refocus → nessuna riapertura WS"; FAIL=1; fi

echo "--- REALTIME ---"
settingsTab || { echo "  [warn] Settings non raggiungibile (procedo su fallback)"; }
sleep 4
# Il toggle è nello stesso row del testo "Battery saver · recommended": tap a destra.
rt=$(nodeXY "Battery saver · recommended")
if [ -z "$rt" ]; then
  # sezione sotto la piega: swipe up per trovarla
  for _ in 1 2 3 4 5 6; do
    adb shell input swipe 540 1800 540 600 300; sleep 1
    rt=$(nodeXY "Battery saver · recommended")
    [ -n "$rt" ] && break
  done
fi
if [ -z "$rt" ]; then echo "  FAIL: testo 'Battery saver · recommended' introvabile"; FAIL=1; else
  set -- $rt
  # Lo Switch è il nodo checkable nella metà destra dello schermo, alla stessa
  # riga del testo (Δy ~64px sotto il centro del testo). Trova il nodo reale.
  sw=$(dump | grep -oE '<node[^>]*checkable="true"[^>]*>' \
    | grep -oE '(bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"|text="[^"]*")' \
    | paste - - | grep 'bounds' \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk -v t="$2" '($1+$3)/2>700 && (($2+$4)/2-t)>-150 && (($2+$4)/2-t)<150 {print int(($1+$3)/2), int(($2+$4)/2); exit}')
  if [ -n "$sw" ]; then
    echo "  switch trovato a $sw"
    adb shell input tap $sw; sleep 8; shot rt-01-toggled
  else
    echo "  [warn] switch non trovato, fallback (927,$(($2+64)))"
    adb shell input tap 927 "$(($2+64))"; sleep 8; shot rt-01-toggled
  fi
  o4=$(openCnt); d4=$(discCnt)
  echo "  open=$o4 disconnect=$d4 dopo toggle Realtime"
fi

adb shell input keyevent 3; sleep 10   # HOME → in REALTIME il WS deve RESTARE aperto
shot rt-02-background
o5=$(openCnt); d5=$(discCnt)
echo "  open=$o5 disconnect=$d5 dopo background REALTIME"
if [ "$d5" -gt "$d4" ]; then echo "  FAIL: REALTIME background → disconnect inatteso"; FAIL=1; fi

echo "--- crash check ---"
if adb logcat -d | grep -A3 "FATAL EXCEPTION" | grep -q "$PKG"; then
  echo "FATAL found"; FAIL=1
else
  echo "  no FATAL (app)"
fi
if adb shell pidof "$PKG" >/dev/null; then echo "  app alive"; else echo "  APP MORTA"; FAIL=1; fi

if [ "$FAIL" = 0 ]; then echo "VERIFY OK"; else echo "VERIFY FAIL"; exit 1; fi
