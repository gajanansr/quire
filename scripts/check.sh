#!/usr/bin/env bash
# The verification gate. Never commit unless this exits 0.
# :core runs JUnit 5 on plain JVM; :app runs JUnit 4 + Robolectric.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
if [ -d app ]; then
  ./gradlew :core:test :app:testDebugUnitTest "$@"
else
  ./gradlew :core:test "$@"
fi
