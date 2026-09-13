#!/usr/bin/env bash
# Validates the Play store listing before you spend twenty minutes uploading it.
#
# Every limit here is one the Play Console enforces at upload, after the build, at
# the end of the process — a 31-character title is not a warning, it is a rejected
# form with your changelog still unsaved. Same for a feature graphic that is 1024x512
# because an export preset moved.
#
# Character counts, not bytes: Play counts characters, and "Folio — Offline Book
# Reader" is 27 characters and 29 bytes. `wc -c` would pass a title Play rejects.
#
# Deliberately not part of scripts/check.sh: that gate is the test suite, and it
# should not go red because a store graphic has not been drawn yet.
set -uo pipefail
cd "$(dirname "$0")/.."

python3 - <<'PY'
import os
import struct
import sys

META = "fastlane/metadata/android/en-US"
IMAGES = f"{META}/images"
SHOTS = f"{IMAGES}/phoneScreenshots"

failures = []
warnings = []


def fail(message):
    failures.append(message)
    print(f"  FAIL  {message}")


def warn(message):
    warnings.append(message)
    print(f"  WARN  {message}")


def ok(message):
    print(f"  ok    {message}")


def text_limit(path, limit, label):
    if not os.path.isfile(path):
        fail(f"{label}: {path} is missing")
        return
    # Trailing newlines are an editor artefact, not content; Play strips them.
    body = open(path, encoding="utf-8").read().rstrip("\n")
    if not body.strip():
        fail(f"{label}: {path} is empty")
    elif len(body) > limit:
        fail(f"{label}: {len(body)} characters, limit {limit} ({path})")
    else:
        ok(f"{label}: {len(body)}/{limit} characters")


def png_size(path):
    """Width and height from the IHDR chunk. No imaging library needed."""
    with open(path, "rb") as f:
        header = f.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    return struct.unpack(">II", header[16:24])


def exact_png(path, width, height, label):
    if not os.path.isfile(path):
        fail(f"{label}: {path} is missing — run scripts/generate-play-graphics.sh")
        return
    try:
        w, h = png_size(path)
    except ValueError as e:
        fail(f"{label}: {path} is {e}")
        return
    if (w, h) != (width, height):
        fail(f"{label}: {w}x{h}, Play requires exactly {width}x{height} ({path})")
    else:
        ok(f"{label}: {w}x{h}")


print("Listing text")
text_limit(f"{META}/title.txt", 30, "title")
text_limit(f"{META}/short_description.txt", 80, "short description")
text_limit(f"{META}/full_description.txt", 4000, "full description")

print("\nChangelogs")
changelogs = f"{META}/changelogs"
if not os.path.isdir(changelogs) or not os.listdir(changelogs):
    fail(f"no changelog in {changelogs}")
else:
    for name in sorted(os.listdir(changelogs)):
        if name.endswith(".txt"):
            # 500 is the Play Console's limit for a release note.
            text_limit(f"{changelogs}/{name}", 500, f"changelog {name}")

print("\nGraphics")
exact_png(f"{IMAGES}/icon.png", 512, 512, "listing icon")
exact_png(f"{IMAGES}/featureGraphic.png", 1024, 500, "feature graphic")

print("\nPhone screenshots")
shots = []
if os.path.isdir(SHOTS):
    shots = sorted(
        f for f in os.listdir(SHOTS)
        if f.lower().endswith((".png", ".jpg", ".jpeg"))
    )
if len(shots) < 2:
    # A warning, not a failure: screenshots need a running app, and this script has
    # to stay useful on a machine that cannot start one. docs/release.md makes the
    # blocking check the human one, at upload time.
    warn(
        f"{len(shots)} screenshots in {SHOTS}; Play requires at least 2 "
        "and will not let you publish without them (see the README there)"
    )
else:
    for name in shots:
        path = f"{SHOTS}/{name}"
        if not name.lower().endswith(".png"):
            ok(f"{name}: not a PNG, dimensions not checked")
            continue
        w, h = png_size(path)
        short, long_ = min(w, h), max(w, h)
        if not (320 <= short and long_ <= 3840):
            fail(f"{name}: {w}x{h}; each side must be 320-3840 px")
        elif long_ > 2 * short:
            fail(f"{name}: {w}x{h}; the long side may not exceed twice the short side")
        else:
            ok(f"{name}: {w}x{h}")

print()
if failures:
    print(f"{len(failures)} problem(s) would be rejected at upload.")
    sys.exit(1)
if warnings:
    print(f"Listing text and graphics are valid. {len(warnings)} thing(s) still to supply.")
    sys.exit(0)
print("Listing is complete and within every Play limit.")
PY
