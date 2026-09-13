<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" width="96" alt="Folio">

# Folio

**A quiet place to read.** Your books never leave your device.

[**gajanansr.github.io/folio**](https://gajanansr.github.io/folio/)

[![CI](https://github.com/gajanansr/folio/actions/workflows/ci.yml/badge.svg)](https://github.com/gajanansr/folio/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

Folio is an Android reading app for EPUB, PDF and plain text. It has no account, no
sync, no analytics, no ads, and **no `INTERNET` permission at all** — not as a promise,
but as something the operating system enforces. A book you open in Folio is a file on
your phone and stays one.

## What it does

- **Reads EPUB, PDF and TXT.** A PDF is reflowed into real paragraphs so type size and
  theme actually work, rather than being a fixed page you pinch at.
- **Sets text like a book** — justified, hyphenated, first-line indents, no paragraph
  gaps, a measure capped for readability, and widows and orphans pushed to the next
  page.
- **Remembers your place** to the character, and keeps it across a change of type size.
- **Five themes** — Paper, Sepia, E-ink, Night, Black — researched against what Kindle,
  Apple Books and real electrophoretic panels do. E-ink is strictly greyscale, with a
  test asserting every token has chroma exactly zero.
- **Highlights.** Long-press a word, drag to choose a passage, and keep it.
- **Shares a passage as a card** you can dress in any of the five palettes.
- **A reading habit** — a daily goal, a streak, and home-screen widgets.
- **Scanned books are shown as pages**, honestly labelled, rather than run through OCR
  that would invent words the author did not write.

## Build it

```bash
git clone https://github.com/gajanansr/folio.git
cd folio
./scripts/check.sh                       # the gate: JVM tests for :core and :app
./gradlew :app:assembleDebug             # an installable APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 21 and the Android SDK (platform 36) are the only requirements. `scripts/env.sh`
points at a local Homebrew toolchain when one is present and otherwise leaves your
environment alone.

## How it is put together

Two modules, and the split is load-bearing:

| | |
|---|---|
| **`:core`** | Pure JVM. Extraction, reflow, pagination, chapter detection, metadata, the habit model. No Android imports, so it is testable at speed — JUnit 5. |
| **`:app`** | Android. Compose UI, Room, WorkManager, the PDF and OCR-free scan paths. JUnit 4 with Robolectric, because Robolectric is JUnit 4 only. |

The interesting problems live in `:core`: turning positioned glyphs on a PDF page back
into paragraphs, deciding where a page breaks, and refusing to invent structure that
is not in the book. `docs/superpowers/plans/` records how each part was built and
`PROGRESS.md` is the running log, including the bugs and what they taught.

## Testing

`./scripts/check.sh` must exit zero before any commit. There is a device gate too,
`./scripts/check-device.sh`, for the parts that need real hardware — PDF rendering and
widget layout.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). The short version: tests first, never commit
red, never weaken a test to make it pass, and no new dependencies without a reason
that survives being written down.

## Privacy

[The policy](https://gajanansr.github.io/folio/privacy.html), published from
[docs/privacy-policy.md](docs/privacy-policy.md) by `scripts/build-site.py` so the
hosted page and the repository cannot drift apart. Folio collects nothing. There is
nowhere for it to send anything to.

## The site

`site/` is a static page with no build step and no third-party requests — no fonts, no
analytics, no CDN. It deploys to GitHub Pages on every push that touches it.

## Licence

[MIT](LICENSE). Bundled fonts and icons carry their own — see [NOTICE](NOTICE).
