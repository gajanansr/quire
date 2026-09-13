#!/usr/bin/env bash
# Screenshot whatever is on screen right now.
#
#   ./scripts/shot.sh              -> /tmp/quire-shot.png
#   ./scripts/shot.sh reader-bug   -> /tmp/quire-reader-bug.png
#
# This is how a bug gets described precisely: take one, tell me the name, and I
# can look at exactly what you are looking at instead of guessing from words.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
source scripts/device.sh
DEVICE=$(resolve_device) || { echo "no device connected" >&2; exit 1; }
OUT="/tmp/quire-${1:-shot}.png"
adb -s "$DEVICE" exec-out screencap -p > "$OUT"
echo "$OUT"
