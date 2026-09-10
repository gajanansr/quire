#!/usr/bin/env bash
# Boot the AVD headless if it is not already up, and wait for it.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
if adb devices | grep -q 'emulator-.*device'; then echo "emulator already up"; exit 0; fi
nohup emulator -avd folio_test -no-window -no-audio -no-snapshot \
  -gpu swiftshader_indirect > /tmp/emu.log 2>&1 &
adb start-server >/dev/null 2>&1 || true
for i in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && {
    echo "emulator booted after ~$((i*5))s"; exit 0; }
  sleep 5
done
echo "emulator failed to boot within 300s" >&2; exit 1
