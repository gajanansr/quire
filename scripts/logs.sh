#!/usr/bin/env bash
# Follow the app's log, filtered to things that matter.
#
#   ./scripts/logs.sh          follow live (ctrl-C to stop)
#   ./scripts/logs.sh --dump   print what has happened so far and exit
#
# Shows crashes, warnings and errors from Folio's own process plus any stack
# trace, and drops the system chatter that buries them.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
source scripts/device.sh
DEVICE=$(resolve_device) || { echo "no device connected" >&2; exit 1; }

PID=$(adb -s "$DEVICE" shell pidof app.folio.android 2>/dev/null | tr -d '\r' || true)
if [ -n "$PID" ]; then
  echo "following app.folio.android (pid $PID) on $DEVICE" >&2
  FILTER=(--pid="$PID")
else
  echo "app is not running; showing crashes only" >&2
  FILTER=()
fi

if [ "${1:-}" = "--dump" ]; then
  adb -s "$DEVICE" logcat -d "${FILTER[@]}" -v brief 2>/dev/null \
    | grep -vE "^(D|V)/" | tail -80
else
  adb -s "$DEVICE" logcat "${FILTER[@]}" -v brief 2>/dev/null | grep -vE "^(D|V)/"
fi
