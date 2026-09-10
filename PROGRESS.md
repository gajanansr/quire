# Folio — Build Progress

State file for the autonomous overnight build. **Read this first on every wake-up.**
Append to the log; never rewrite history.

## How to work

1. `source scripts/env.sh` before anything (JDK 21 — Homebrew's default 26 breaks AGP).
2. Read `docs/superpowers/specs/2026-09-11-folio-android-design.md` (the spec) and the
   current plan in `docs/superpowers/plans/`.
3. Pick the next unchecked task. Work it TDD: failing test → run it → implement →
   run it → commit.
4. Gate: `./scripts/check.sh` must exit 0 before every commit. **Never commit red.**
5. Tick the task's checkboxes in the plan file, append a log entry below, commit.

## Rules

- **Never commit red.** A failing gate means fix or revert, not push on.
- **Never weaken a test to make it pass.** If a test is wrong, say why in the log.
- **Never bump a pinned version** (see the plan's Global Constraints). Kotlin stays
  2.3.21; Apache PDFBox stays 2.0.37 and test-only. If a pin genuinely blocks progress,
  stop and log it rather than changing it.
- **Conservatism rule.** Reflow must never delete content it is not confident about.
  A test asserting removal must also assert the surviving body text.
- **If blocked for two consecutive wake-ups on the same thing, stop and report.**
  Do not thrash.

## Plan sequence

- [ ] Plan 1 — `:core` pipeline · `docs/superpowers/plans/2026-09-11-folio-pipeline.md`
- [ ] Plan 2 — persistence + import (Room, WorkManager, SAF) · *to be written*
- [ ] Plan 3 — design system + Library/Details UI · *to be written*
- [ ] Plan 4 — reader + pagination + bookmarks · *to be written*
- [ ] Plan 5 — habits, errors, polish · *to be written*

When a plan's tasks are all ticked, write the next plan from the spec using the same
structure, commit it, then continue. Later plans should incorporate what was actually
learned (thresholds that moved, fixtures that surprised us).

## Environment

| | |
|---|---|
| JDK (build) | 21.0.12.1 at `/opt/homebrew/opt/openjdk@21/...` |
| Android SDK | `/opt/homebrew/share/android-commandlinetools`, platform 36, build-tools 36.1.0 |
| Emulator | AVD `folio_test`, API 36 arm64 google_apis — boots headless in ~40s |
| Gate | `./scripts/check.sh` |
| Emulator boot | `./scripts/emulator.sh` |

## Open questions for the morning

Record anything needing a human decision here rather than guessing.

- **Truncated PDFs import silently.** PDFBox's lenient parser recovers a truncated
  file and reports a plausible page count, so a partial book can be imported as if
  whole. Pinned by `a truncated pdf opens successfully with partial content`. The
  pipeline should compare recovered content against the file's declared page count
  and flag a shortfall — but "flag" how, in UI terms, is a design question the handoff
  does not cover. Suggest treating it like `reflowFailed`: import it, and offer
  "Read original PDF".

## Log

### 2026-09-11 — session start

Environment verified: JDK 21, Android SDK 36, emulator boots headless in ~40s with adb
attached. Spec written and committed. Plan 1 (pipeline, 20 tasks) written and committed.

Two version traps found and avoided before any code was written:

- Kotlin 2.4.20 is current, but KSP's latest is 2.3.12 and does not support it. Room in
  Plan 2 needs KSP, so the whole project is pinned to Kotlin 2.3.21.
- PdfBox-Android's latest (2.0.27.0) ports Apache PDFBox **2.x**. The JVM test double is
  therefore pinned to PDFBox 2.0.37, not the current 3.0.8, or the tests would exercise
  APIs the Android implementation does not have.

Next: Plan 1, Task 1 — Gradle scaffold and toolchain smoke test.

### 2026-09-11 01:40 — Tasks 1–5 complete, 19 tests green

Gate: `./scripts/check.sh` → BUILD SUCCESSFUL, 19 tests passed.

- **Task 1** Gradle scaffold. Wrapper 9.7.1, Kotlin 2.3.21, JDK 21 toolchain. Every
  pinned version resolved on first try. Smoke test asserts JDK ≥ 21 and that Apache
  PDFBox **2.x** is on the test classpath.
- **Task 2** Fixture corpus — 12 files generated, all verified by assertion rather than
  by eyeball: `scanned.pdf` (510K) confirmed to have **no** extractable text layer,
  `single-column.pdf` confirmed to have one, `header-footer.pdf` confirmed to repeat its
  running header on ≥5 pages, `large.pdf` confirmed at 420 pages.
- **Task 3** `FolioConstants` — the eight tunables in one place.
- **Task 4** Normalized model. Character-based `ReadingPosition`; `progressAt` clamps to
  0..1 rather than throwing on a stale position, and returns 0 for an empty book instead
  of dividing by zero.
- **Task 5** TXT parser. Conservative heading detection: a 130-char sentence beginning
  "Chapter 4" stays body text, and text with no markers becomes one chapter rather than
  invented structure. Both are explicit tests.

One shell gotcha worth remembering: heredocs into a not-yet-created directory abort the
whole script. `mkdir -p` first.

Next: Task 6 — EPUB container parsing (`EpubContainer`), against `cleanEpub`,
`epubNoNav`, and `malformedEpub`.

### 2026-09-11 01:55 — Tasks 6–9 complete, 55 tests green

Gate: `./gradlew :core:test --rerun-tasks` → 55 tests, 0 failures.

- **Task 6** `EpubContainer`. container.xml → OPF → spine/metadata/nav, with `..`
  collapsing in href resolution. Supports both EPUB 3 nav and EPUB 2 NCX. Every
  accessor returns null or empty on a broken book instead of throwing.
- **Task 7** `EpubHtmlConverter`. Structure preserved, presentation discarded. Nested
  inline styles compose (`<strong>bold <em>both</em></strong>` yields both on "both"),
  adjacent same-style spans merge, and an unrecognised element degrades to a paragraph
  rather than being dropped.
- **Task 8** `EpubParser`. One chapter per spine document. Titles prefer nav, fall back
  to the first heading, then stay null — never invented.
- **Task 9** `PdfTextSource` + the Apache PDFBox test implementation. Verified it
  surfaces what reflow actually needs: per-run x/y/width/height, median font size,
  and bold/italic from the font name. On `chaptered.pdf` the title run measures >1.5×
  the body median, so font-size heading detection has real signal to work with.

**Finding worth keeping — lenient PDF recovery.** The `corrupt.pdf` fixture (truncated
to a third) did **not** fail to open: PDFBox rebuilt it by scanning for objects and
reported 6 pages with 697 chars, no exception. My test had assumed a throw.

Rather than soften the assertion I split the case in two, because both are real:

- `corruptPdf()` is now genuinely unparseable — PDF magic bytes then noise — and must
  fail on open.
- `truncatedPdf()` keeps the old truncation and is pinned by a test asserting it opens
  successfully *with a measurable shortfall* against the intact file.

The second is the dangerous one: a truncated book imports looking healthy. Detecting
the shortfall belongs to the pipeline (Task 19/20), not the text source, and is now
recorded as an open question below.

Next: Task 10 — `LineAssembler`, clustering runs into lines by y-band.
