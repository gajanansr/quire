#!/usr/bin/env bash
# Boot the AVD if it is not already up, and wait for it.
#
#   ./scripts/emulator.sh          headless — what check-device.sh uses
#   ./scripts/emulator.sh --window visible on screen, for watching the app
#
# The windowed mode uses the host GPU: swiftshader renders in software, which is
# right for a headless test run and far too slow to judge a scroll or a page turn.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

WINDOW=0
[ "${1:-}" = "--window" ] && WINDOW=1

if adb devices | grep -q 'emulator-.*device'; then
  echo "emulator already up"; exit 0
fi

if [ "$WINDOW" = "1" ]; then
  nohup emulator -avd folio_test -no-audio -no-snapshot -gpu host \
    > /tmp/emu.log 2>&1 &
else
  nohup emulator -avd folio_test -no-window -no-audio -no-snapshot \
    -gpu swiftshader_indirect > /tmp/emu.log 2>&1 &
fi

adb start-server >/dev/null 2>&1 || true
for i in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && {
    echo "emulator booted after ~$((i*5))s"; exit 0; }
  sleep 5
done
echo "emulator failed to boot within 300s" >&2; exit 1
