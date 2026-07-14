#!/usr/bin/env bash
# Build the debug APK and install it on a phone connected over adb.
#
# Usage: scripts/install-debug.sh [device-serial]
# With several devices attached, pass the serial shown by `adb devices`
# (or set ANDROID_SERIAL).

set -eu

cd "$(dirname "$0")/.."

if ! command -v adb >/dev/null 2>&1; then
    echo "error: adb is not on PATH (install Android platform-tools)" >&2
    exit 1
fi

if [ "$#" -gt 1 ]; then
    echo "usage: $0 [device-serial]" >&2
    exit 2
fi
if [ "$#" -eq 1 ]; then
    ANDROID_SERIAL="$1"
    export ANDROID_SERIAL
fi

devices="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
device_count="$(printf '%s' "$devices" | grep -c . || true)"

if [ "$device_count" -eq 0 ]; then
    echo "error: no device in 'device' state; check the cable and the USB-debugging prompt (adb devices)" >&2
    exit 1
fi
if [ "$device_count" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "error: several devices connected; pass a serial or set ANDROID_SERIAL:" >&2
    printf '  %s\n' $devices >&2
    exit 1
fi

./gradlew :app:assembleDebug

apk="app/build/outputs/apk/debug/app-debug.apk"
adb install -r "$apk"
echo "Installed $apk on ${ANDROID_SERIAL:-$devices}"
