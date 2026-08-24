#!/usr/bin/env bash
# Launcher build lato sandbox: costruisce (se serve) l'immagine builder ed esegue
# Gradle dentro il container, con SDK e cache Gradle su volumi persistenti.
#
# Uso:
#   ./docker/build.sh                 → assembleDebug
#   ./docker/build.sh assembleRelease test
#
# NOTA path: il daemon Docker gira sull'host reale → i -v usano
# /home/andrea/projects/... (= /workspace qui in sandbox). MAI /host_root.
set -euo pipefail

SANDBOX_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="xon-trader-builder:1.0"
HOST_REPO="/home/andrea/projects/xon-trader"
HOST_SDK="/home/andrea/projects/android-sdk"
HOST_GRADLE="/home/andrea/projects/gradle-home"
# Keystore di DEBUG: senza questa persistenza AGP ne genera uno NUOVO a ogni
# build → ogni APK debug ha firma diversa e l'install -r sull'emulatore fallisce.
HOST_ANDROID_HOME="/home/andrea/projects/android-home"

TASKS="${*:-assembleDebug}"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "[build] costruisco immagine $IMAGE ..."
  docker build -t "$IMAGE" -f "$SANDBOX_ROOT/docker/builder.Dockerfile" "$SANDBOX_ROOT/docker"
fi

mkdir -p "$HOST_SDK" "$HOST_GRADLE" "$HOST_ANDROID_HOME"

exec docker run --rm \
  -v "$HOST_REPO:/work/repo" \
  -v "$HOST_SDK:/opt/android-sdk" \
  -v "$HOST_GRADLE:/root/.gradle" \
  -v "$HOST_ANDROID_HOME:/root/.android" \
  -w /work/repo \
  "$IMAGE" bash -c '
    set -euo pipefail
    if [ ! -x /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager ]; then
      bash docker/sdk-setup.sh
    fi
    printf "sdk.dir=/opt/android-sdk\n" > local.properties
    gradle --no-daemon '"$TASKS"'
  '
