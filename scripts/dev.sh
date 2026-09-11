#!/usr/bin/env bash
# Put the app in front of you.
#
# Boots the emulator with a window if nothing is connected, builds, installs and
# launches. If your phone is plugged in with USB debugging on, it goes there
# instead and the emulator is left alone.
#
# Reinstalls keep app data, so your library, reading position and streak survive
# every rebuild. Pass --fresh to wipe them.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
source scripts/device.sh

FRESH=0
[ "${1:-}" = "--fresh" ] && FRESH=1

adb start-server >/dev/null 2>&1 || true
if ! DEVICE=$(resolve_device); then
  echo "nothing connected — booting the emulator with a window"
  ./scripts/emulator.sh --window
  DEVICE=$(resolve_device) || { echo "still no device" >&2; exit 1; }
fi

NAME=$(adb -s "$DEVICE" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "target: $DEVICE ($NAME)"

if [ "$FRESH" = "1" ]; then
  echo "wiping app data"
  adb -s "$DEVICE" uninstall app.folio.android >/dev/null 2>&1 || true
fi

# installDebug builds and installs in one step and is incremental: after the first
# run only what actually changed is recompiled.
ANDROID_SERIAL="$DEVICE" ./gradlew :app:installDebug -q
adb -s "$DEVICE" shell am start -n app.folio.android/.MainActivity >/dev/null
echo "Folio is running on $DEVICE"
