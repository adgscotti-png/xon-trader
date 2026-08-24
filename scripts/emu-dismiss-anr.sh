#!/usr/bin/env bash
# Chiude gli ANR di sistema ("Process ... isn't responding") tappando Wait.
# Uso: emu-dismiss-anr.sh
set -u
export PATH=$PATH:/opt/android-sdk/platform-tools
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml 2>/dev/null; }
for _ in $(seq 1 4); do
  xml=$(dump)
  if printf '%s' "$xml" | grep -q "isn't responding"; then
    b=$(printf '%s' "$xml" | grep -oE '<node[^>]*text="Wait"[^>]*>' | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | tail -1)
    if [ -n "$b" ]; then
      x1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
      y1=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
      x2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
      y2=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
      adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
      echo "ANR dismiss: Wait tappato @ $(( (x1+x2)/2 )),$(( (y1+y2)/2 ))"
      sleep 2
    else
      echo "ANR presente ma bottone Wait non trovato"
      return 1
    fi
  else
    echo "nessun ANR a schermo"
    return 0
  fi
done
echo "ANR non chiuso dopo 4 tentativi"
return 1
