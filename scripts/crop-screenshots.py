#!/usr/bin/env python3
"""Crop raw device screenshots to something Play will accept.

    python3 scripts/crop-screenshots.py docs/screenshots/reader.png ...

A modern phone screenshot is 1080x2400 — a 20:9 panel. **Play rejects it.** The rule
is that the long side may not exceed twice the short side, and 2400 is more than
2x1080. Nothing warns you until the upload fails, which is why `check-listing.sh`
tests for it and why this exists.

The fix is a crop rather than a letterbox, and it improves the picture either way:
what comes off is the status bar at the top and the gesture bar at the bottom, neither
of which tells a prospective reader anything about the app. Content is centred in what
remains, so the crop is taken proportionally from each end rather than all from one.
"""
import pathlib
import sys

try:
    from PIL import Image
except ImportError:  # pragma: no cover - developer environment only
    sys.exit("Pillow is required: pip3 install Pillow")

# Play's constraint, as a ratio of long side to short side.
MAX_RATIO = 2.0


def crop(path: pathlib.Path) -> str:
    with Image.open(path) as image:
        width, height = image.size
        if height <= width * MAX_RATIO:
            return f"{path.name}: {width}x{height} already within {MAX_RATIO:g}:1"

        target = int(width * MAX_RATIO)
        excess = height - target
        # Weighted towards the top: a status bar is taller than a gesture bar, and
        # taking it all from one end shifts the page off centre.
        top = int(excess * 0.58)
        cropped = image.crop((0, top, width, top + target))
        cropped.save(path)
        return f"{path.name}: {width}x{height} -> {width}x{target} (cut {excess}px)"


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 1
    for name in argv:
        path = pathlib.Path(name)
        if not path.exists():
            print(f"missing {path}", file=sys.stderr)
            return 1
        print(crop(path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
