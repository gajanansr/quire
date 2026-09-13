#!/usr/bin/env bash
# Instrumented tests. Needs the emulator; boots it if not already up.
# Covers PdfRenderer and ML Kit, neither of which Robolectric can shadow.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
./scripts/emulator.sh
./gradlew :app:connectedDebugAndroidTest "$@"

# connectedAndroidTest uninstalls both APKs when it finishes, which leaves the
# device with no Quire on it — surprising in the middle of a session where someone
# is looking at the app. Put it back.
echo "reinstalling Quire (the test run uninstalls it)"
./scripts/dev.sh
