#!/usr/bin/env bash
# Rebuild and reinstall whenever a source file changes, so the app on screen
# tracks the code without you running anything.
#
#   ./scripts/watch.sh        rebuild + reinstall + relaunch on change
#   ./scripts/watch.sh --stay rebuild + reinstall, but do not relaunch
#
# --stay leaves the app on whatever screen it was on. Android restarts the process
# on reinstall either way; the difference is whether it comes back to the Library
# or you tap in yourself. Reading position, library and streak always survive —
# they are in the database, not the APK.
#
# Polls rather than using fswatch so it needs nothing installed. A 2s poll over a
# few hundred source files costs nothing next to the build it triggers.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
source scripts/device.sh

RELAUNCH=1
[ "${1:-}" = "--stay" ] && RELAUNCH=0

DEVICE=$(resolve_device) || {
  echo "no device — run ./scripts/dev.sh first" >&2; exit 1; }
echo "watching for changes; target $DEVICE. ctrl-C to stop."

# Anything Gradle compiles or packages. Excludes build output, or every rebuild
# would trigger the next one.
fingerprint() {
  find core/src app/src core/build.gradle.kts app/build.gradle.kts \
       gradle/libs.versions.toml settings.gradle.kts \
       -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.xml' -o -name '*.toml' \) \
       -newermt '1970-01-02' -exec stat -f '%m %N' {} + 2>/dev/null | sort | md5
}

LAST=$(fingerprint)
while true; do
  sleep 2
  NOW=$(fingerprint)
  [ "$NOW" = "$LAST" ] && continue

  # Settle: wait for edits to stop before building, so saving three files in a
  # row is one build rather than three.
  while true; do
    sleep 1
    AGAIN=$(fingerprint)
    [ "$AGAIN" = "$NOW" ] && break
    NOW=$AGAIN
  done
  LAST=$NOW

  echo ""
  echo "── change detected $(date '+%H:%M:%S') ─────────────────────────────"
  if ANDROID_SERIAL="$DEVICE" ./gradlew :app:installDebug -q 2>&1 | tail -20; then
    [ "$RELAUNCH" = "1" ] && \
      adb -s "$DEVICE" shell am start -n app.folio.android/.MainActivity >/dev/null
    echo "✓ updated $(date '+%H:%M:%S')"
  else
    echo "✗ build failed — app on device left as it was"
  fi
done
