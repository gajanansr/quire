#!/usr/bin/env bash
# The verification gate. Never commit unless this exits 0.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh
./gradlew :core:test "$@"
