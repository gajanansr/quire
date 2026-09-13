#!/usr/bin/env bash
# Regenerates the two Play Store graphics from the app's own mark and colours.
#
#     ./scripts/generate-play-graphics.sh
#
# Writes fastlane/metadata/android/en-US/images/{icon,featureGraphic}.png. Commit
# the result; Play needs the files, not the ability to make them.
#
# Run this after any change to res/drawable/ic_launcher_foreground.xml or
# res/values/ic_launcher_colors.xml. The store icon is the one copy of the brand
# nobody thinks to update, and it is the copy most people see first.
#
# Needs nothing installed beyond the JDK the build already requires: JDK 21 runs a
# single .java file directly, and Java2D draws the curves. Headless on purpose, so
# this works over ssh and in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/env.sh

OUT="fastlane/metadata/android/en-US/images"
mkdir -p "$OUT"

java -Djava.awt.headless=true scripts/graphics/PlayGraphics.java "$OUT"

echo
echo "Checking them against Play's limits:"
./scripts/check-listing.sh
