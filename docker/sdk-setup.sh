#!/usr/bin/env bash
# Esegue DENTRO il builder: installa cmdline-tools + pacchetti SDK nel volume persistente.
set -euo pipefail

: "${ANDROID_HOME:=/opt/android-sdk}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

if [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "[sdk-setup] cmdline-tools già presenti, skip download"
else
  echo "[sdk-setup] scarico cmdline-tools..."
  TMP="$(mktemp -d)"
  curl -fsSL "$CMDLINE_TOOLS_URL" -o "$TMP/tools.zip"
  unzip -q "$TMP/tools.zip" -d "$TMP"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  mv "$TMP/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$TMP"
fi

SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

echo "[sdk-setup] accetto licenze..."
# Input FINITO: 'yes |' con pipefail muore di SIGPIPE quando sdkmanager esce
printf 'y\n%.0s' $(seq 1 500) | "$SDKM" --licenses >/dev/null || true

echo "[sdk-setup] installo platform-tools, android-35, build-tools 34.0.0 ..."
printf 'y\n%.0s' $(seq 1 50) | "$SDKM" "platform-tools" "platforms;android-35" "build-tools;34.0.0" >/dev/null

echo "[sdk-setup] OK — contenuto $ANDROID_HOME:"
ls "$ANDROID_HOME"
