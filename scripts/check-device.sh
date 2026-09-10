#!/usr/bin/env bash
# Instrumented tests. Needs the emulator; boots it if not already up.
# Covers PdfRenderer and ML Kit, neither of which Robolectric can shadow.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
./scripts/emulator.sh
./gradlew :app:connectedDebugAndroidTest "$@"
