#!/usr/bin/env python3
"""Regenerate Folio's icon drawables from Lucide.

Run from the repo root after editing ICONS or bumping LUCIDE_VERSION:

    python3 scripts/generate-icons.py

Fetches each icon from the pinned lucide-static release and writes
app/src/main/res/drawable/ic_<name>.xml. Only the icons Folio actually uses are
bundled; Lucide is not a dependency, so nothing is fetched at build time and
nothing at runtime.

VectorDrawable understands only <path>, so circle/line/rect primitives are
converted to equivalent path data rather than dropped. Everything stays a stroke:
Lucide's consistency comes from one stroke width and round caps across the whole
set, and flattening to fills would throw that away.
"""
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET

LUCIDE_VERSION = "0.544.0"
OUT_DIR = "app/src/main/res/drawable"

# Every icon Folio uses, and where. Keep this list and FolioIcons.kt in step;
# FolioIconsTest fails if they drift apart.
ICONS = [
    "library",          # nav: Library
    "bookmark",         # nav: Bookmarks, and the bookmark action
    "settings",         # nav: Settings
    "chevron-right",    # list rows that open something
    "chevron-left",     # back
    "plus",             # add a book
    "check",            # milestone achieved, import step done
    "share-2",          # share
    "list",             # table of contents
    "type",             # typography controls
    "book",             # empty states
    "flame",            # reading streak
    "award",            # milestones and level
    "download",         # save a share card
    "more-horizontal",  # more share destinations
    "image",            # share to stories
    "send",             # share to a message
    "copy",             # copy a passage to the clipboard
    "trash-2",          # remove a bookmark
    "highlighter",      # highlight a selected passage
]

# Icons that point along the reading direction and must flip when the layout does.
MIRRORED = {"chevron-right", "chevron-left", "share-2", "send", "list"}

NS = "{http://www.w3.org/2000/svg}"


def circle(e):
    cx, cy, r = float(e.get("cx")), float(e.get("cy")), float(e.get("r"))
    # Two half-arcs: VectorDrawable has no circle primitive.
    return f"M{cx - r},{cy} a{r},{r} 0 1,0 {2 * r},0 a{r},{r} 0 1,0 {-2 * r},0"


def line(e):
    return f"M{e.get('x1')},{e.get('y1')} L{e.get('x2')},{e.get('y2')}"


def rect(e):
    x, y = float(e.get("x", 0)), float(e.get("y", 0))
    w, h = float(e.get("width")), float(e.get("height"))
    rx = float(e.get("rx", 0) or 0)
    if rx <= 0:
        return f"M{x},{y} h{w} v{h} h{-w} z"
    return (f"M{x + rx},{y} h{w - 2 * rx} a{rx},{rx} 0 0 1 {rx},{rx} "
            f"v{h - 2 * rx} a{rx},{rx} 0 0 1 {-rx},{rx} h{-(w - 2 * rx)} "
            f"a{rx},{rx} 0 0 1 {-rx},{-rx} v{-(h - 2 * rx)} "
            f"a{rx},{rx} 0 0 1 {rx},{-rx} z")


HANDLERS = {"path": lambda e: e.get("d"), "circle": circle,
            "line": line, "rect": rect}


def fetch(name, into):
    url = (f"https://cdn.jsdelivr.net/npm/lucide-static@{LUCIDE_VERSION}"
           f"/icons/{name}.svg")
    dest = os.path.join(into, f"{name}.svg")
    result = subprocess.run(
        ["curl", "-sS", "-f", "-m", "20", "-o", dest, url],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise SystemExit(f"could not fetch {name}: {result.stderr.strip()}")
    return dest


def convert(src, name):
    root = ET.parse(src).getroot()
    paths = []
    for el in root:
        tag = el.tag.replace(NS, "")
        handler = HANDLERS.get(tag)
        if handler is None:
            raise SystemExit(f"{name}: unhandled SVG element <{tag}>")
        paths.append(handler(el))

    body = "\n".join(
        '    <path\n'
        f'        android:pathData="{d}"\n'
        '        android:strokeColor="#FF000000"\n'
        '        android:strokeWidth="2"\n'
        '        android:strokeLineCap="round"\n'
        '        android:strokeLineJoin="round" />'
        for d in paths
    )
    mirror = '\n    android:autoMirrored="true"' if name in MIRRORED else ""
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- Lucide "{name}", ISC licensed. Generated from lucide-static\n'
        f'     {LUCIDE_VERSION} by scripts/generate-icons.py. Edit the generator,\n'
        '     not this file. Stroke colour is a placeholder: every use site tints\n'
        '     it through FolioIcon, so the icon follows the active theme. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        '    android:viewportWidth="24"\n'
        f'    android:viewportHeight="24"{mirror}>\n'
        f'{body}\n'
        '</vector>\n'
    )


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name in ICONS:
            src = fetch(name, tmp)
            res = "ic_" + name.replace("-", "_")
            with open(os.path.join(OUT_DIR, res + ".xml"), "w") as f:
                f.write(convert(src, name))
            print(f"  {res}")
    print(f"{len(ICONS)} icons written to {OUT_DIR}")


if __name__ == "__main__":
    main()
