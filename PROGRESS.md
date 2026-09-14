# Folio — Build Progress

## Where things stand

**v0.1 complete. 394 JVM tests + 15 device tests, all passing.**
The app installs, runs, imports real books, and reads them.

| | |
|---|---|
| Verify (JVM) | `./scripts/check.sh` |
| Verify (device) | `./scripts/check-device.sh` |
| Run it | `./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Sideload | `~/Desktop/Folio-0.1.0.apk` (debug-signed, all four ABIs, 67 MB) |
| **See it** | `./scripts/dev.sh` — boots a windowed emulator (or uses a plugged-in phone), builds, installs, launches |
| **Live reload** | `./scripts/watch.sh` — rebuilds and reinstalls on every source change |
| **Screenshot** | `./scripts/shot.sh <name>` — writes `/tmp/quire-<name>.png` |
| **Logs** | `./scripts/logs.sh` (follow) or `--dump` (what already happened) |
| Screenshots | `docs/screenshots/` |
| **Release** | `docs/release.md` — clean checkout to published |
| Store listing | `fastlane/metadata/android/en-US/` · check with `./scripts/check-listing.sh` |
| Privacy | `docs/privacy-policy.md` · `docs/play-data-safety.md` |
| Spec | `docs/superpowers/specs/2026-09-11-folio-android-design.md` |
| Plans | `docs/superpowers/plans/` |

**Working end to end:** import (EPUB, TXT, text PDF, and scanned PDF shown as its
original printed pages — *corrected 2026-09-13: this line used to say "via real
on-device ML Kit OCR". OCR was removed; see `PageRasterizer` and `PdfPipeline.process`.
The store listing was written against the code, not against this paragraph*)
→ reflow and chapter detection → Library → Book Details → Reader with
measured pagination, page turns, typography, table of contents, four themes,
bookmarks, and a PDF fallback for books that could not be reflowed. Reading position
persists and resumes exactly, including across a type-size change.

Onboarding → goal → import → read → recorded minutes → streak. The habit loop
closes: reading in the app writes real minutes, verified on device. Theme and
typography persist across launches.

**Deliberately not built:** text highlights (bookmarks cover the load-bearing half),
real share destinations (the handoff forbids brand marks without the actual SDKs),
and cloud sync (explicitly out of scope).

**Deliberate departures from the handoff, at the user's direction:** the theme set.
The handoff shipped Light / Pale / Dark / E-ink, with Light at L=0.985 and Pale at
L=0.965 — two percent apart, so they read as one theme — and E-ink defined as a
grayscale filter over Light. Folio now ships five, researched against what Kindle,
Apple Books and real e-ink hardware actually do:

| | Page | Ink | Job |
|---|---|---|---|
| Paper | #FDF5EF | #111B28 | the handoff's Light, unchanged. Daylight. |
| Sepia | #F1E5D0 | #3B2A1C | the warm page every e-reader ships |
| E-ink | #E8E8E8 | #141414 | strictly neutral, 15:1 like an E Ink Carta panel |
| Night | #1D1E20 | #D0D1D3 | dark grey, not black — halation |
| Black | #000000 | #C4C4C4 | OLED and pitch-dark rooms |

Every token of E-ink has chroma exactly zero, asserted in a test: an electrophoretic
panel is greyscale hardware, which is why a Kindle Paperwhite cannot show sepia or
green at all. It is not pure black on pure white either — Carta 1200 measures 15:1
to 17:1 and its white is reflective, not emitted. Night avoids pure black because
white-on-black halation is a wall for the ~half of people with some astigmatism;
true black is its own theme for those who want it.

Body text clears WCAG AA on all five, and no two pages are within 0.06 of each other
on any channel. Both are tests, not intentions.

**One open question for you** is recorded under "Open questions" below.

---

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

- [x] Plan 1 — `:core` pipeline **COMPLETE** (153 tests) · `docs/superpowers/plans/2026-09-11-folio-pipeline.md`
- [x] Plan 2 — persistence + import **COMPLETE** (216 JVM + 14 device tests)
- [x] Plan 3 — design system + Library/Details UI **COMPLETE** (287 JVM + 15 device)
- [x] Plan 4 — reader + pagination + bookmarks **COMPLETE** (337 JVM + 15 device)
- [x] Plan 5 — habits, settings, share sheets **COMPLETE** (387 JVM + 15 device)
- [x] Reading reminders **COMPLETE** (663 JVM) · `docs/superpowers/plans/2026-09-13-folio-notifications.md`

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

- **Back does nothing visible on three screens.** `back()` in `ui/nav/QuireBack.kt`
  has no case for `goalJustReached`, `importProgress` or `failure`, so on the goal,
  import-progress and error screens a press pops the stack *underneath* the screen
  the reader is looking at — the screen stays, and the press is spent. Pre-existing,
  not from the reminders work, and surfaced while fixing exactly this shape of bug
  for the reminder invitation. `invitationVisible` shows the pattern that fixes it:
  say which screen is on top, once, and let Back and the renderer read the same
  answer. Not done here because it reaches three screens this branch does not own.

- **`reflowFailed` means two different things.** `PdfPipeline` sets it both for a
  scan with no chapters (line 68) and for a low-confidence reflow that keeps a real
  chapter list (line 104), and Book Details offers "read the pages" for both. The
  reminder copy had to special-case it to avoid announcing a real chapter title at a
  page number. Anything else that reads a position without knowing which reader wrote
  it has the same trap waiting. Two flags — "could not be reflowed" and "is read by
  page" — would say what is actually meant, but that is a migration and a pipeline
  change, so it is recorded rather than taken.

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
- **Task 3** `QuireConstants` — the eight tunables in one place.
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

### 2026-09-11 02:10 — Tasks 10–12 complete, 82 tests green

Gate: `./gradlew :core:test --rerun-tasks` → 82 tests, 0 failures.

- **Task 10** `LineAssembler`. Clusters runs by baseline, tolerance scaled to page
  type size. Inserts a space between runs only where the producer left a visible gap,
  so runs split mid-word rejoin without a spurious space.
- **Task 11** `ColumnDetector` + a column-aware `LineAssembler.assemble` overload.
  Detection runs on raw runs, not lines, because line assembly merges across a gutter
  by design and would hide the gap being looked for.
- **Task 12** `HeaderFooterDetector`. Removes running heads and page numbers; body
  text survives.

**Three bugs found, all in the direction that destroys content. Worth recording.**

1. *Two-column fixture was unbalanced.* `wrap(LOREM, 34)` produces ~24 lines and my
   `take(20)`/`drop(20)` split left 20 lines against 4 — the right side carried 17% of
   the text, so `MIN_SIDE_SHARE` correctly read it as a ragged margin, not a gutter.
   The detector was right and the fixture was wrong; columns are now split evenly.

2. *Page height was inferred, not supplied.* `strip` derived page height from the
   tallest line. On a page whose tallest line **is** the running header, the inferred
   height collapses the margin band onto the body. `strip` now requires real
   `pageHeights` from document geometry.

3. *`MARGIN_BAND = 0.12` deleted the top line of every page.* On US Letter with 1-inch
   margins the first body line sits ~9% down, inside a 12% band. Because consecutive
   pages' body lines normalize to the same string once digits are masked, they looked
   like a recurring header and were removed. Now 0.06, and promoted from a private
   companion into `QuireConstants` with the reasoning attached, plus a test asserting
   it can never again reach the text block.

That third one is the exact failure the conservatism rule exists to prevent, and it
was only caught because `body text is never removed` asserts the survivors rather
than just asserting that something was removed. Worth keeping that test shape.

Next: Task 13 — `ParagraphAssembler`.

### 2026-09-11 02:25 — Tasks 13–14 complete, 102 tests green

Gate: `./gradlew :core:test --rerun-tasks` → 102 tests, 0 failures.

- **Task 13** `ParagraphAssembler`. Breaks on short final lines, indent shifts, and
  gaps beyond the leading. Also emits headings, which needed a plan correction: the
  plan had heading detection in `ChapterDetector` (Task 16), but by then blocks have
  lost font metrics. Detection moved here, the last step that still has `Line` and
  its type sizes. `ChapterDetector` will consume the resulting `Heading` blocks.
- **Task 14** `Dehyphenator`. The spec's worked example now resolves: on the real
  fixture, `Distributed sys- / tems are a col- / lection of...` becomes
  `Distributed systems are a collection of...`.

**Two bugs, both from degenerate measurement.**

1. *Leading is meaningless with two lines.* `medianLeading` took the median of the
   inter-baseline gaps, so with two lines the only gap **was** the median and no gap
   could ever exceed it — paragraph breaks stopped being detected. Now falls back to
   a type-size estimate below 3 samples, and caps the empirical value: a "median" far
   larger than the type size is separation, not leading.

2. *Merge gaps accumulated from the head.* The joined line keeps the first line's
   position for layout, and I was measuring the next gap from that same head, so each
   link in a hyphenation chain measured further than the last until a valid chain
   looked like a paragraph break. Real fixture broke at the second link
   (`col- lection`) while synthetic two-line cases passed. Adjacency is now judged
   against the tail of the merge.

Both were only visible because the tests run against generated PDFs as well as
hand-built lines. The synthetic cases passed in each instance.

Next: Task 15 — `ReflowPipeline`, composing lines → columns → furniture → hyphens →
paragraphs, with a confidence score.

### 2026-09-11 02:40 — Tasks 15–16 complete, 122 tests green

Gate: `./gradlew :core:test --rerun-tasks` → 122 tests, 0 failures.

- **Task 15** `ReflowPipeline`. Composes columns → lines → furniture → hyphens →
  paragraphs, then stitches page seams. Passed on the first run.

  Two things per-page assembly cannot do, handled at pipeline level: a word
  hyphenated across a page boundary (the baseline gap across a seam is meaningless —
  bottom of one page to top of the next), and a paragraph continuing across a
  boundary (joined only when the previous page ends without terminal punctuation
  **and** the next opens lowercase, so a chapter ending mid-clause joins while two
  complete sentences do not).

  Confidence blends paragraph length, character retention, and furniture-detection
  quality. Below threshold it returns the extracted text with a low score, never an
  empty document.

- **Task 16** `ChapterDetector` + `HeadingSignals`. Outline → typographic headings →
  naming patterns, then a single chapter if nothing scores. Patterns cover arabic,
  roman, and word numbers across chapter/part/book/section/canto/act, plus named
  divisions like prologue and epilogue.

**Finding: two-line chapter openings.** The chaptered fixture sets each opening as a
label line (`Chapter 1`) above a title line (`The Weight of Silence`). Both match, so
three chapters came out as six. This is an extremely common book convention, not a
fixture quirk. Consecutive candidates now collapse into one boundary that starts at
the label and takes its name from the strongest member — the typographic title rather
than the label — so the table of contents reads "The Weight of Silence".

Next: Task 17 — `ScannedDetector`.

### 2026-09-11 02:55 — PLAN 1 COMPLETE. Tasks 17–20 done, 153 tests green

Gate: `./gradlew :core:test --rerun-tasks` → 153 tests, 0 failures, 20 test classes.

- **Task 17** `ScannedDetector`. Sampled median chars/page. Samples the head plus a
  spread of the tail, capped at 40 pages, so classifying a 400-page book does not
  cost as much as extracting it. A healthy book with the odd image page is left
  alone — those are plates, and the median is what says so.
- **Task 18** `OcrEngine` / `PageRasterizer` interfaces and fakes. `toTextRuns()` is
  the join that matters: once OCR output wears the same shape as extracted text,
  reflow, structure detection and normalization are *literally the same code* for a
  scan as for a text PDF. A test asserts OCR lines reflow through that path.
- **Task 19** `PdfPipeline` + `Normalizer`. Per-page OCR only where a text layer is
  missing, folded back into the page stream. A page that fails to recognise is
  skipped rather than aborting the import. Passed first run.
- **Task 20** `FormatDetector` and the end-to-end suite. Detection is by signature,
  not extension, so the PNG named `.epub` is refused at the door instead of failing
  confusingly inside a parser.

**Plan 1 delivers a complete, tested book-processing library with no Android
dependency.** EPUB, TXT, text PDF and scanned PDF all normalize into the same model;
every fixture either imports Ready or fails with a reason, and nothing throws.

The end-to-end suite pins the reader's contract directly: for every format, chapter
offsets are cumulative, `totalChars` reconciles, position START is 0% and the end of
the last chapter is exactly 100%. Those are the invariants resume depends on.

---

## Plan 2 begins: persistence and import

Next up is the first Android module. Expect friction the pipeline did not have:
AGP 9.4.0, Room 2.8.5 with KSP 2.3.12 (the reason Kotlin is pinned to 2.3.21),
WorkManager 2.11.2, and the emulator. The AVD `folio_test` boots headless in ~40s.

Writing Plan 2 next.

### 2026-09-11 03:10 — Android module stands up, Plan 2 written, 156 tests green

Gate: `./scripts/check.sh` now runs both modules → 156 tests, 0 failures.

Rather than write Plan 2 from assumed version compatibility, I stood the Android
module up first and wrote the plan from what actually worked. Four toolchain facts
came out of it, each of which would have cost a chunk of the night if it had been
baked into a plan as a guess:

1. **AGP 9 has built-in Kotlin support.** Applying `org.jetbrains.kotlin.android`
   is now a hard failure: *"The plugin is no longer required for Kotlin support
   since AGP 9.0."* Removed it; the plugin block is AGP + compose + serialization +
   KSP only.

2. **Compose BOM 2026.09.00 requires `compileSdk 37`.** `material-ripple 1.12.1`
   refuses to build against 36. The platform installs as `android-37.2`, which AGP 9
   addresses as `compileSdk = 37` plus `compileSdkMinor = 2`. My earlier platform
   listing missed it because the grep required a trailing space and the newer
   platforms carry dotted names.

3. **Robolectric 4.16 caps `targetSdk` at 36.** With `targetSdk = 37` every JVM-side
   Android test dies on `Package targetSdkVersion=37 > maxSdkVersion=36`. compileSdk
   and targetSdk are independent, so the app compiles against 37 and targets 36. That
   keeps DAO tests running on the JVM, which is worth more than targeting the newest
   API. Written into the plan as a do-not-"fix" constraint.

4. **`:app` must use JUnit 4.** Robolectric has no JUnit 5 runner, so `:app` omits
   `useJUnitPlatform()` while `:core` keeps JUnit 5. Two modules, two frameworks,
   deliberately.

**Kotlin 2.3.21 was the right pin.** `:app:kspDebugKotlin` ran and generated Room's
code against AGP's built-in Kotlin; three DAO tests pass under Robolectric. Had I
taken Kotlin 2.4.20, KSP would have had no compatible release and Room would have
been dead in the water.

Also added `scripts/count-tests.sh` since counts now span two modules.

Next: Plan 2, Task 1 — `BookStore`, the on-disk chapter store.

### 2026-09-11 03:25 — Plan 2 Tasks 1–2 complete, 178 tests green

Gate: `./scripts/check.sh` → 178 tests, 0 failures.

- **Task 1** `BookStore`. Chapter-per-file on disk; streams the original rather than
  buffering it, since a book can be hundreds of megabytes.
- **Task 2** `BookRepository` + `LibraryRow`. Room metadata joined with disk content
  behind one type. The library query joins progress in SQL so the grid renders from a
  single emission rather than a per-book lookup.

**Two bugs, both found by tests rather than by reading the code.**

1. *Deleting a book orphaned its progress and bookmark rows.* Invisible until an id is
   reused, at which point a deleted book's reading position resurrects. Dependent rows
   are now deleted explicitly with the book.

2. *`BookStore.bookDir()` called `mkdirs()`, and the read paths used it.* So merely
   reading a missing chapter recreated the directory of a deleted book — every lookup
   of an absent book left an empty folder behind. `bookDir()` is now a pure path and
   only the write paths create anything. Caught because the delete test happened to
   call `loadChapter` before checking the directory was gone; a differently ordered
   test would have missed it entirely. There is now an explicit test that reading a
   missing book creates nothing.

Next: Task 3 — `AndroidPdfTextSource` on PdfBox-Android, the real counterpart to
`:core`'s Apache-PDFBox test double.

### 2026-09-11 03:50 — Plan 2 Tasks 3–4 complete. 186 JVM tests + 7 on device

Gates: `./scripts/check.sh` → 186 tests, 0 failures.
`./scripts/check-device.sh` → 7 instrumented tests, 0 failures. **First device run.**

- **Task 3** `AndroidPdfTextSource` on PdfBox-Android, verified against the same
  fixture corpus as the JVM reader via Gradle test fixtures. One test runs `:core`'s
  `ReflowPipeline` unchanged on the Android source — header stripping and
  de-hyphenation included — which is the architectural claim made concrete.
- **Task 4** `AndroidPageRasterizer` and `MlKitOcrEngine`, both verified on the
  emulator. **Real ML Kit recovers "Distributed systems" from a rendered page of
  `scanned.pdf`**, clears the confidence gate, and its output reflows through
  `:core`'s pipeline with no OCR-specific branch. The scanned-book path now works end
  to end against a real model rather than a fake.

**Five obstacles, all environmental, all now encoded as constraints.**

1. *Robolectric cannot shadow `PdfRenderer`* — `NoSuchMethodError:
   FileDescriptor.getOwnerId$()`. Worth noting one of the three tests "passed" anyway,
   because `runCatching` swallowed that very error and the test only asserted failure.
   A test passing for the wrong reason is worse than one that fails. All three moved
   to `androidTest` and the assertion now checks the exception *type*.

2. *KSP exhausted Metaspace.* `[ksp] java.lang.OutOfMemoryError: Metaspace` with no
   `gradle.properties` present. Now `-Xmx4g -XX:MaxMetaspaceSize=1g`.

3. *Apache PDFBox's jars collide on `META-INF/DEPENDENCIES`* when packaged into a test
   APK. Added packaging exclusions.

4. *Apache PDFBox cannot run on Android at all* — it needs `java.awt`
   (`NoClassDefFoundError: Ljava/awt/Point;`), so the fixture generator cannot run on
   device. Fixtures are now generated on the host by `:core:generateFixtureAssets` and
   packaged as test assets. The task lives in `:core` because Gradle 9 forbids
   resolving another project's configurations, and AGP 9 rejects `Provider` in the
   SourceSet API, so the asset path is a plain `File`.

5. *`getInstrumentation().context` has no usable data directory.* The test package is
   never launched, so writing to its `cacheDir` fails with ENOENT. Assets must be read
   from the **test** context but written to the **target** context's cache. Two
   contexts, deliberately different — this cost two wrong fixes before I stopped
   guessing and inspected the APK, which proved the assets were present and pointed at
   the write side instead.

Next: Task 5 — `BookImporter`.

### 2026-09-11 04:10 — Plan 2 Tasks 5–6 complete, 215 tests green

Gate: `./scripts/check.sh` → 215 tests, 0 failures.

- **Task 5** `BookImporter`. Copies *then* identifies, deliberately: the pipeline then
  works on a stable local file rather than a content URI whose permission can be
  revoked mid-read, and detection reads the bytes actually kept rather than what the
  extension claimed. Every failure path funnels through one cleanup, so a
  half-imported book never reaches the Library — asserted directly. 14 tests covering
  all four formats plus unsupported, corrupt, empty, and out-of-space.
  The importer test drives the **production** `AndroidPdfTextSource`, not a JVM
  stand-in, so it exercises the PDF path the app actually ships.

- **Task 6** `ImportProgressStore`, `ImportWorker`, `QuireWorkerFactory`,
  `ImportCoordinator`. Unique work per book id with `KEEP`, so a double tap cannot
  import the same file twice. Progress is written atomically — temp file then rename —
  because a process killed mid-write would otherwise leave a truncated state file that
  reads as corrupt on restart; there is a test that a truncated file reads as absent
  rather than throwing, and another that no `.tmp` survives a save.

  OCR page counts are persisted so a restarted import does not redo recognition it
  already finished. On a 400-page scan that is the difference between resuming and
  starting over.

Only two things left in Plan 2: `QuireApp` wiring (Task 7) and the instrumented
import tests (Task 8).

### 2026-09-11 04:35 — PLAN 2 COMPLETE. 216 JVM tests + 14 device tests, all green

Gates: `./scripts/check.sh` → 216, 0 failures. `./scripts/check-device.sh` → 14, 0 failures.

Tasks 7–8 done: `QuireApp` wires the graph and supplies WorkManager's factory, and
the instrumented suite runs the whole import path on device with **no fakes below the
picker** — real `PdfRenderer`, real ML Kit, real Room.

**The device suite immediately earned its keep by catching a bug nothing else could.**

`importsAScannedPdfThroughRealOcr` failed with `EMPTY_DOCUMENT`. ML Kit was working —
its own test passed — but the whole book was vanishing. Cause: **ML Kit reports boxes
in raster pixels at 300 DPI, while the pipeline reasons in PDF points.** On a Letter
page that is a 4× mismatch, so every recognised line landed far above the page's top
margin band. The header detector then did exactly its job — saw identical lines
recurring at the top of every page and removed them as running furniture — and deleted
the entire book.

Two independent defects, both real:

1. *No coordinate conversion.* `OcrPage` now carries the image size it was measured
   in, and `toTextRuns(target)` scales into page space. A unit test asserts a
   2550×3300 raster maps back inside a 612×792 page.
2. *The fixture repeated identical text on all six pages.* Real books do not, and it
   made the header detector look wrong when it was right. `singleColumnPdf` now opens
   each page with its own sentence.

**A third bug came from Task 7 itself.** Registering `QuireApp` in the manifest made
Robolectric instantiate it, and `QuireGraph` built `MlKitOcrEngine()` eagerly —
`TextRecognition.getClient` needs ML Kit's context, which does not exist off-device.
That single line of work-in-a-constructor failed **42 tests at once**, across classes
with nothing to do with OCR. The graph and the recogniser are now both lazy, so
constructing the object graph does no work and `Application.onCreate` stays cheap.

Worth noting the shape of all three: none were logic errors in the algorithms, which
are the part with 150 tests. They were seams — pixels versus points, eager versus
lazy, a fixture that did not resemble a book.

---

## Plan 3 begins: design system and Library UI

From here the work is Compose and the design handoff. Expect the OKLCH conversion
risk flagged in the spec (§14.1) to be the first real hazard: an unverified transform
shifts every colour in the app slightly, and it is easy to miss.

### 2026-09-11 05:00 — Plan 3 written; Tasks 1–3 complete, 230 tests green

Gate: `./scripts/check.sh` → 230 tests, 0 failures.

**The spec's flagged colour risk (§14.1) is closed.** The handoff specifies every
token in OKLCH and Compose has no OKLCH literal, so an unverified transform would
have shifted every colour in the app slightly — wrong on every screen and nearly
invisible in review.

Rather than paste hex values, `oklch()` does the conversion and the palettes are
written in the handoff's own numbers, so the handoff stays the source of truth. The
transform is checked against values computed independently from Ottosson's OKLab
specification: **two implementations of the same spec agreeing on all 23 tokens.** A
conversion that only agrees with itself would prove nothing.

Sanity of the result is reassuring: light `bg #FDF9F6` is a warm off-white, `ink
#111B28` a near-black blue, `accent #214F7C` a muted editorial blue.

Also asserted, because they are the kind of thing that silently rots:

- E-ink's palette is **identical** to Light. The handoff says e-ink is a grayscale
  post-process, so if that equality ever breaks, someone has hand-tuned a fifth
  palette by mistake.
- Light's background is lighter than its ink and Dark's is darker — a guard against
  a copy-paste inversion.
- Button text clears a luminance delta of 0.3 against its background in all four
  themes, so no theme ships unreadable buttons.

**Fonts are bundled, not downloaded.** Downloadable Fonts needs Play Services and a
connection, which would break the offline guarantee for a purely cosmetic reason.
Eight OFL-1.1 faces (408KB total) are committed as resources with the licence.

One piece of my own work I threw away: the theme file initially carried a
`private typealias` block whose only purpose was to stop unused imports failing the
build. That is a hack pretending to be code — the imports are simply gone now.

Next: Task 4 — Pill navigation, then the Library screen on real data.

### 2026-09-11 05:25 — Tasks 4–5 complete. THE APP RUNS. 246 tests green

Gate: `./scripts/check.sh` → 246 tests, 0 failures.
The APK installs, launches, and renders. Screenshot: `docs/screenshots/01-library-empty.png`.

- **Task 4** Pill navigation — three destinations, only the active one labelled.
- **Task 5** Library screen, `LibraryState`, `BookCover`, empty and error states,
  `QuireStrings`, `MainActivity` with the SAF picker, and `QuireRoot`.

**Seeing the screen immediately paid for itself.** "Good evening" was clipped against
the left edge: the populated branch gets its gutters from the grid's `contentPadding`,
and the empty branch — having no grid — had none. Every test passed. No test would
ever have caught it. Fixed and re-verified against a second screenshot.

The rendering confirms the design system is right: the warm off-white ground, the
Source Serif headline against Work Sans body, the near-black button, and the
accent-blue pill are all visibly the handoff's palette. The OKLCH work holds up on
a real screen, not just in assertions.

**Two decisions where I chose honesty over appearance.**

1. *The pill is translucent, not blurred.* The handoff calls it "glass", and my first
   attempt reached for `Modifier.blur` — which blurs a composable's **own content**,
   not the backdrop. It would have been decoration that does nothing. Compose has no
   backdrop-blur API; real backdrop sampling needs a custom RenderNode or
   window-level `setBackgroundBlurRadius`. The code says so plainly rather than
   shipping a no-op that looks like an implementation.

2. *The header subtitle reports book count, not a streak.* The handoff shows "7 day
   reading streak · 12 min today", but the habit system does not exist until Plan 5.
   Rendering an invented streak to someone on their first day is exactly the "fake
   streak calculations" the brief forbids, so it shows what is actually known and
   will show the real streak when there is one.

Also deleted a second piece of my own scaffolding: `QuireNav` briefly had a phantom
`Box` and an identity `matchParentSizeSafe()` that existed only to make a dead
`Modifier` chain look used.

`QuireStrings` now centralises copy with two guards: no `FailureReason` name can
reach the screen, and every pipeline stage maps to the handoff's words — asserted,
because "ocr" appearing in the UI would violate the brief's no-jargon rule.

Next: Task 6 — Book Details on real data.

### 2026-09-11 05:50 — Tasks 7–8 complete. 251 JVM + 15 device tests

Gates: `./scripts/check.sh` → 251, 0 failures. Device → 15, 0 failures.
Screenshot: `docs/screenshots/02-library-populated.png`.

Took Task 7 before Task 6: Book Details needs a book to exist, so the import flow
had to land first.

- **Task 7** `AddBookSheet`, `ImportProgressScreen`, and the whole flow wired through
  `MainActivity` — SAF picker, WorkManager, progress, error, back to Library.
- **Task 8** `ErrorState` and `EmptyState` with the designed copy.

**The Library now renders four real books imported through the real pipeline**,
including a scanned PDF that went through actual ML Kit OCR, with a Continue Reading
card showing 42% read from the database.

Getting that screenshot needed one non-obvious step. `connectedAndroidTest`
**uninstalls both APKs when it finishes**, taking the seeded database with it — the
app was simply gone, and `am start` reported "Activity class does not exist".
Installing both APKs with `adb install` and driving the seeder with
`adb shell am instrument` leaves everything in place.

**Two more defects only a screenshot could find**, both invisible to 251 passing tests:

1. The Continue Reading thumbnail rendered `A Hist or...` — the cover swatch used one
   type size for both a 54dp thumbnail and a full grid tile. It now measures itself
   and shows a monogram when too small for a legible title.
2. The last grid row's caption sat under the floating nav pill. Bottom content
   padding now clears it.

That is four layout defects found by looking and zero found by testing, which is
about what I would expect: tests verify behaviour, and none of these were behaviour.

**A note on the seed data, not a bug.** Two books show the title "A History of Quiet
Things" — the EPUB's metadata title, and the TXT file whose first line is the same
string. `TxtParser` takes the opening line as the title, which is right for plain
text; the fixtures just happen to collide.

Next: Task 6 — Book Details, then Plan 4, the Reader.

### 2026-09-11 06:15 — Reading estimates + schema migration, 272 tests green

Gate: `./scripts/check.sh` → 272 tests, 0 failures.

Groundwork for Book Details:

- **`ReadingEstimates`** in `:core` — page counts, time remaining, and the handoff's
  pace sentence. Named "estimates" throughout on purpose: a reflowed book has no
  fixed pages, so a page number is a nominal measure for the stat strip and never a
  location. Positions stay character-based.

  Two judgement calls worth recording. A measured reading pace is **withheld** below
  ten minutes of evidence rather than extrapolated from a few seconds — a confidently
  wrong "3 minutes left" is worse than the honest default. And implausible paces are
  rejected outright, so a phone left open on one page does not redefine someone's
  speed. There is a test for each.

- **`dc:description` and `dc:subject`** captured from EPUB, feeding the Synopsis tab
  and the genre chips. The alternative — generating a synopsis — would have been
  fabrication; an absent description now simply shows nothing.

**A real Room migration, not a destructive fallback.** Nothing has shipped, so
`fallbackToDestructiveMigration` would have been the quick path. It is also the kind
of line that stays in place until the day it silently deletes a reader's entire
library. Migration 1→2 was **verified against the live on-device database**: all four
previously imported books survived, and both columns are present. Checked by reading
the device's SQLite directly rather than inferring it from the absence of a crash.

**A trap that has now caught me twice**, worth writing down: `:core` uses
`kotlin.test.assertTrue(condition, message)` and `:app` uses JUnit 4's
`assertTrue(message, condition)` — the same call with the arguments reversed. It
fails at compile time, so it costs a minute rather than a night, but the two-framework
split (forced by Robolectric being JUnit 4 only) has this standing cost.

Next: the Book Details screen itself, then Plan 4 — the Reader.

### 2026-09-11 06:40 — PLAN 3 COMPLETE. 287 JVM + 15 device tests

Gate: `./scripts/check.sh` → 287, 0 failures. Screenshot: `docs/screenshots/03-book-details.png`.

Book Details renders from real persisted state: 42% complete, Ch. 2, pages, time
left, the handoff's pace sentence, genre chips from `dc:subject`, and a synopsis from
`dc:description`.

The screen's rule is that unknowns say so. No chapters shows a dash rather than
"Ch. 1" of nothing; no description shows "This book didn't come with a description."
rather than a generated summary; and `PDF_OCR` reads simply as "PDF", because whether
a scan needed recognising is Folio's business, not the reader's. Each is a test.

**A stale-cache bug in my own test helper, worth recording.** After adding
`dc:description` I re-seeded and the field was still empty. The cause was
`DeviceFixtures`, which cached assets **by filename**: once a fixture existed on the
device it was never replaced, so the regenerated EPUB never arrived and the test was
quietly exercising last week's book. It now compares content length. This is the
insidious kind — nothing failed, the data was simply wrong, and a test that checks a
stale fixture passes while proving nothing.

Two pieces of my own work removed rather than left lying around: a
`BookDetailsViewModel` made dead when I inlined its loader, and a doc comment my
patch had stranded above the wrong function.

**Plan 3 is done.** The design system, navigation, Library, Book Details, Add Book,
import progress, and the error and empty states all exist and are verified on device.

---

## Plan 4 next: the Reader

The remaining core of the product: measured pagination, typography controls, table of
contents, themes in the reader, persistent position, bookmarks and highlights. This
is where the character-based `ReadingPosition` chosen in the spec finally earns its
keep — resume has to survive a typography change, which is exactly what a page-number
model cannot do.

### 2026-09-11 07:20 — THE READER WORKS. Plan 4 Tasks 1–4, 326 tests green

Gate: `./scripts/check.sh` → 326, 0 failures. Screenshot: `docs/screenshots/04-reader.png`.

Real book text, in Source Serif on the reader ground, resumed at the saved chapter.
Tap the outer thirds to turn pages, the middle to toggle chrome; swipe works too.

**Three bugs, and the interesting one is a unit mismatch.**

1. *The chapter title printed twice.* Most EPUBs open a chapter with an `<h1>` of its
   own title, and the Reader was drawing its header above that. Suppressed when the
   chapter's first block already says it.

2. *The paginator measured a different box than the text rendered into.* It was given
   the full screen while the text sat inside padding, so it packed more than fit.
   Rather than duplicate the padding in two places — where the two would drift apart
   — the padding now lives on the text column and that column reports its own size.
   One box by construction.

3. **`bodyLineHeightPx` was computed from `fontSizeSp` and compared against a pixel
   viewport.** sp is scale-independent; the viewport is device pixels. On this
   emulator's 2.75x screen the paginator believed lines were nearly three times
   shorter than they render, and confidently overfilled every page. The symptom —
   a clipped last line — looks exactly like text going missing, which is why it is
   worth naming: nothing crashed, no test failed, and the arithmetic was internally
   consistent. `TypographySettings` now carries `pixelsPerSp`, documented as
   load-bearing, with a test asserting a denser screen needs more pages.

All three were found by looking at the screen. The 326 tests remain useful for what
they cover — conservation, ordering, boundaries — but none of them could have caught
a paginator that is perfectly self-consistent in the wrong units.

`Paginator` also gained a `firstPageInsetPx` so it can budget for chrome sharing the
text's box, with tests that an inset reduces the first page, loses no characters, and
terminates even when larger than the page.

Next: Task 5 typography sheet, Task 6 contents, Task 7 bookmarks, then the §19
resume loop on device.

### 2026-09-11 07:55 — Typography and contents sheets, live themes. 328 tests

Gate: 328, 0 failures. Screenshots: `05-typography.png`, `06-theme-dark.png`.

Switching to Dark from inside the Reader recolours the sheet and the page behind it
at once, accent included. The OKLCH palettes hold up in both directions.

- **Task 5** Typography sheet — 2×2 font grid, size stepper honouring the handoff's
  15–24 bounds, alignment, and the four theme swatches. Every control repaginates
  immediately, off the main thread, applied in one state update so a size change
  never shows a half-laid-out page.
- **Task 6** Contents sheet — flat list, current chapter in accent with a dot.

Two small decisions worth their comments:

- **A chapter index file.** The TOC needs titles, and reading four hundred chapter
  files to build a list would defeat the whole reason chapters are stored separately.
  `writeChapters` now also writes `chapters/index.json` holding refs only. A test
  asserts the index is not itself counted as a chapter — which is exactly what the
  existing `chapters are written one file each` test caught the moment I added it.
- **Theme swatches carry a letter, not just a colour.** Light and E-ink share a
  palette by design, so colour alone cannot tell them apart.

Next: Task 7 bookmarks and highlights, Task 8 the PDF fallback viewer, then the
§19 resume loop on device.

### 2026-09-11 08:25 — Section 19's resume loop verified for every format. 337 tests

Gate: 337, 0 failures.

`ResumeLoopTest` runs the brief's loop —
**import → process → library → open → read → close → reopen → resume** — for EPUB,
TXT, text PDF, scanned PDF and the 420-page book, against the real repository, the
real on-disk store and the real paginator. Only text *measurement* is faked, which
is the one part that genuinely needs a device.

All five resume to the **exact** saved position.

The case that matters most is the last one: close a book at 19sp, reopen it at 24sp,
and land on the same sentence. The page number the reader was on no longer exists —
that is precisely why positions are character offsets and never page numbers, and it
is now pinned by a test rather than by an argument in a design document.

Bookmarks are saved with a snapshot of the text at that position, so a bookmark
still means something if the book is ever reprocessed and offsets shift. The
confirmation is the handoff's brief "Bookmark added" toast rather than a persistent
badge — the reading page is meant to disappear, and a permanent marker would undo
that.

Highlights are deferred with the share sheets; bookmarks were the load-bearing half.

Remaining in Plan 4: the PDF fallback viewer (Task 8) and the bookmarks list screen.

### 2026-09-11 08:50 — PLAN 4 COMPLETE. The Reader is finished. 337 + 15 tests

Tasks 8–9 done.

- **PDF fallback viewer.** A book Folio could not reflow confidently is offered as
  its original pages rather than rejected, which the brief asks for explicitly.
  Pages rasterize lazily and cache once seen; rendering a long PDF up front would be
  gigabytes of bitmap.
- **Bookmarks list** across the whole library, each row showing the passage rather
  than a position — a chapter and offset mean nothing to someone scanning a list.

Verified against the live device database rather than inferred: one bookmark stored
with snippet "What the River Kept", reading position at chapter 1 offset 40,
progress 0.42.

**Plans 1–4 are complete.** Import, processing, storage, the Library, Book Details
and the Reader all work on a real device with real books.

### 2026-09-11 09:15 — Plan 5 written; habit arithmetic done. 362 tests

Gate: 362, 0 failures. 25 of them on streaks, levels and milestones.

All habit maths is pure functions in `:core/habit`, tested by injecting "today"
rather than by waiting a week. Two rules worth stating, since this is precisely
where the brief warns against fake streak calculations:

- **A streak may end yesterday without breaking.** At 00:01 the reader has not had a
  chance to read today. Zeroing the streak the moment midnight passes would be wrong
  and discouraging; it breaks only once a full day has genuinely gone by.
- **Days are keyed by local epoch-day, not UTC.** Someone finishing at 23:50 has read
  *today*. A UTC key would file it as tomorrow and either grant a streak they did not
  earn or break one they did.

Also guarded: duplicate rollups for the same day cannot inflate a streak, out-of-order
history is handled, and the longest streak survives the current one ending.

**A test of mine was wrong, not the code.** I asserted no milestone title contains a
digit, which failed on "First 10 Minutes" — the handoff's own copy. The design says
no numeric *badge counts* ("5 of 8"), not that a name cannot contain a number. The
test now enforces the structural rule: a milestone is a name and a sentence, with
nowhere to put a count.

**Standing hazard, now four occurrences.** `:core` uses
`kotlin.test.assertTrue(condition, message)`; `:app` uses JUnit 4's
`assertTrue(message, condition)`. Same call, reversed arguments. It fails at compile
time so each instance costs a minute, but four times means it is a property of the
two-framework split — forced by Robolectric being JUnit 4 only — rather than
carelessness. Worth knowing before writing tests in either module.

Next: Task 2, session recording.

### 2026-09-11 09:40 — Session accumulation. 374 tests

Gate: 374, 0 failures. 12 new tests on session recording.

- **Idle time is excluded.** A phone left face-up on a page for an hour is not an
  hour of reading; crediting it would quietly inflate every streak and goal. Activity
  extends an interval, silence beyond the idle timeout ends it, and an abandoned
  session closes at the cutoff rather than staying open forever.
- **Midnight splits.** 23:40 to 00:20 is twenty minutes on each of two days, not
  forty on either — the difference between earning a streak day and being handed one.
- **A glance records nothing.** Sub-minute stretches round to zero and are dropped;
  "1 minute today" from a five-second glance would be a small lie.

**A mistake I made and reversed, worth recording.** Having hit the assertion-order
trap five times, I wrote `scripts/fix-assert-order.py` to fix it mechanically. It
then **broke two files that were already correct**: its three-argument rule cannot
tell `assertEquals(message, expected, actual)` from the perfectly valid
`assertEquals(expectedString, actual, message)`. When the expected value is a String
the two forms are genuinely ambiguous.

I reverted with `git checkout`, deleted the script, and fixed the two real cases by
hand. The conclusion is more useful than the script: **this cannot be automated
safely**, and a tool that silently rewrites correct code is far worse than a compile
error costing a minute. Noted here so the idea is not attempted again.

Next: Task 3 goal selection, Task 4 the habit screens, Task 5 the Library card on
real data.

### 2026-09-11 10:05 — Habit persistence. 387 tests

Gate: 387, 0 failures. Migration 2→3 verified against the live device database —
four books intact, `reading_days` and `app_settings` created, no crash.

`HabitRepository` computes streaks, XP and milestones **on read** from recorded days
rather than caching totals. Caching would mean two sources of truth for the same
number, and the one the reader sees is eventually the stale one.

Two rules with tests behind them:

- **A day stores the goal in force when it was recorded.** Someone who met a
  five-minute goal last week still met it, even if they later aim for thirty.
  Recomputing history against the current goal would silently un-earn streak days
  a reader genuinely earned.
- **Minutes are additive.** A second session in a day adds to the first rather than
  replacing it — an easy overwrite bug that would quietly lose most of a day.

Only the handoff's four goals (5/10/20/30) are accepted; anything else is rejected
rather than silently stored.

Next: the habit screens and the Library card on real data, which removes the last
placeholder in the app.

### 2026-09-11 10:35 — Habit screens; the last placeholder is gone. 387 tests

Screenshots: `08-library-habits.png`, `09-streak.png`.

Seeded ten days of real history on the device — including two missed days — and the
Library now reads "3 day reading streak · 14 min today", with the habit card showing
"3-day streak / Today's goal is done." **Every number on the Library screen is now
derived from stored data.** No placeholder remains anywhere in the app.

The seven-day strip and the four-week heatmap both draw **empty cells for days with
no reading**. That is deliberate: showing only the days someone read would make every
reader look perfect, which is a flattering chart rather than a record of a habit.
The gaps are the honest part.

Copy adapts to what actually happened rather than assuming success:

- No streak yet → "Start a reading habit" and "Read today to begin a streak."
- A broken streak → "Your longest run was N days. Today can start the next."
- Seven days → the handoff's "You've read every day this week."

A reader on their first day is never congratulated on a week they have not had.

Remaining in Plan 5: Settings (Task 6) and the share sheets (Task 7).

### 2026-09-11 11:00 — PLAN 5 COMPLETE. All five plans done. 387 + 15 tests

Final verification, both gates from clean: **387 JVM tests, 15 device tests, zero
failures.** Screenshot: `10-settings.png`.

- **Settings** — grouped cards on the soft canvas, Reading / Library / About.
  Chevrons appear only on rows that do something, so the affordance matches the
  behaviour. "Imported books: 4" is real. "Your books · Never leave this device" says
  the privacy guarantee plainly instead of burying it in a policy.
- **Share sheets** — the 9:16 story card for a quote and for a streak, caption row,
  destinations.

**Share destinations use plain glyphs, not brand marks.** The handoff is explicit
that no real logos were used and none should be added without the actual SDKs and
brand guidelines. Drawing a recognisable mark without permission is a trademark
problem rather than a design shortcut, so "Save" is the destination that genuinely
works and the rest hand off to the system share sheet.

---

## All five plans are complete

Import, processing, OCR, storage, Library, Book Details, the Reader, and the habit
system all work on a real device with real books, on real recorded data.

**Deliberately not built**, each for a stated reason:

- **Text highlights.** Bookmarks cover the load-bearing half — marking and returning
  to a place. Colour-picking a selection is polish on top of that.
- **Real share destinations.** Blocked by the handoff's own instruction about brand
  marks and SDKs; the sheet and cards exist behind a system-share hand-off.
- **Cloud sync.** Explicitly out of scope in the spec and the brief.
- **Onboarding flow.** The goal screen exists as a composable but is not yet wired
  as a first-run gate.

### 2026-09-11 11:45 — v0.1: the four gaps closed. 394 + 15 tests

Asked whether the app was done, I checked rather than answered from memory and found
four gaps — **and one claim of mine that was wrong.** I had written that habits ran
"on real recorded data". They did not: the computation was real, but nothing called
`record()`. The streak in the earlier screenshot came from rows I inserted by hand
with sqlite, and I should have noticed that needing to do so was the symptom.

All four are now closed and verified on device:

1. **Session recording is wired.** `SessionTracker` collects a timestamp on opening
   a book and on every page turn, and flushes on leaving the Reader *and* on the app
   backgrounding — most sessions end with a locked phone, not a back-press, so
   committing only on a clean exit would lose nearly all of them.

   Verified for real: 75 seconds of reading on the emulator wrote
   `reading_days: minutes=1`, and the Library then showed "1 min today". Written by
   the app, not by me.

2. **Theme and typography persist.** Both were held in `mutableStateOf` and reset
   every launch. Now read from and written to settings — confirmed by switching to
   Dark, force-stopping, and relaunching into Dark.

3. **Milestones and Level are reachable**, from the streak screen rather than the
   tab bar: they are things you look at occasionally, not destinations.

4. **Onboarding, Goal and Book Completion screens exist and are wired.** First run
   gates on a stored flag, so it appears once. Verified on a clean install: the
   headline, the goal picker, the choice persisted (`dailyGoalMinutes=5,
   onboarded=1`), and no reappearance on the second launch.

**A real inconsistency surfaced while wiring this.** `SessionTracker` took an
injectable clock but `HabitRepository.todayEpochDay()` ignored its own `nowMs` and
called `LocalDate.now()`. The two could disagree about what day it is — minutes filed
against one day, the streak checked against another. Both now share one clock.

Screenshots added: `11-onboarding.png`, `12-goal.png`, `13-dark-persisted.png`.

### 2026-09-11 — build for a real phone

Packaged a debug APK to sideload. Two defects were in the artifact rather than
the code, and only inspecting the built APK surfaced them.

**The APK requested INTERNET.** Folio makes no network calls; the permission came
from ML Kit, whose bundled recogniser is fully on-device but whose dependency
graph includes Google's datatransport (Clearcut) logging backend. Settings
promises the reader their books never leave the device, and the permission list
contradicted it. Removed with `tools:node="remove"`. Verified on device that all
15 instrumented tests still pass — including the four real ML Kit OCR cases and
the scanned-PDF import — so on-device recognition does not depend on it. Android's
own App info screen now reads "No permissions requested" and "No data used".

Kept the four WorkManager permissions on purpose, reasoning recorded in the
manifest: WAKE_LOCK sustains a long OCR import past screen-off, FOREGROUND_SERVICE
backs expedited work, ACCESS_NETWORK_STATE is read-only and WorkManager evaluates
it for every enqueued job whether or not the job has a network constraint.
Removing it risks a SecurityException at enqueue time for no privacy gain.

**No launcher icon** — it installed as the generic robot. The handoff ships no
image assets and hands the icon system to the implementer, so instead of drawing
a mark the icon is the Source Serif Semibold "F" glyph itself, extracted to a
vector path: the same letter, from the same face, as the wordmark inside the app.

`NoNetworkPermissionTest` reads the merged manifest back and fails on any
unreviewed permission, not only INTERNET. `IcLauncherColorTest` pins the icon's
literals to the OKLCH tokens through the same transform `QuireColors` uses,
because a launcher icon is resolved before any app code runs.

Gates: 399 JVM tests, 15 device tests, 0 failures.

### 2026-09-12 — reader performance and extraction quality

Plan: `docs/superpowers/plans/2026-09-12-folio-performance-and-extraction.md`.
All seven tasks complete. 475 JVM tests, 19 device tests, 0 failures.

**Why long books hung.** The paginator handed the measurer `text.substring(cursor)`
— the entire rest of the block — to work out where one page ended, so a block spread
over P pages was measured about P/2 times over. Counted rather than guessed:

| chapter | shape | characters laid out | ratio |
|---|---|---|---|
| 50k | one block | 492k | 9.8x |
| 100k | one block | 1,916k | 19.2x |
| 200k | one block | 7,563k | 37.8x |
| 400k | one block | 30,051k | **75.1x** |
| 400k | paragraphs | 473k | 1.2x |

The ratio doubling with size is the signature. Ordinary paragraphs hid it entirely,
which is why some books were fine and others hung: the trigger is a *single enormous
block* — a TXT with no blank lines, a PDF whose reflow merged everything. Measuring a
window sized to the page instead brings it to **1.35x at every size**, one measure
call per page. `PaginationCostTest` counts characters rather than timing, so it is
deterministic and states the invariant directly.

Two more: `ContentBlock.plainText` rebuilt a block's whole string on every read, and
the Reader read it per visible block per recomposition — a 400k-character allocation
per frame on a big block, which is what made page turns drag. `Chapter.blockTexts`
derives it once. And paginated chapters are now kept (3-entry LRU keyed on chapter,
viewport, typography and the first-page header inset).

**Extraction.** Books now keep their own covers (EPUB 2 and 3 declarations plus
fallbacks, PDF page one at 72 DPI, nothing invented for TXT). PDF titles come from
the document's metadata, validated — `Microsoft Word - thesis_final_v3.doc` is a real
value — then from the largest type on page one, then from an `Author - Title`
filename, with the filename as the floor. Scanner noise is filtered out by shape and
position rather than confidence, under a hard guarantee that anything reading as
language is kept. Photographed pages are greyscaled and auto-levelled, but only for
books where a three-page sample shows it genuinely helps.

**Two process notes.** Fixtures were cached by filename, so editing one silently left
the old file in place and the new test failed against the previous book — that cost
time twice, and the cache path now carries a version to bump. And the device gate
uninstalls both APKs when it finishes, which left the phone with no Folio on it
mid-session; `check-device.sh` reinstalls afterwards.

## 2026-09-13 — One bad width, a whole book in fragments

The reader was indenting every line and pushing text to the next page. It looked
like a typography bug, and it was not: the paragraphs themselves were wrong.

`Dehyphenator.merge` joined a word split across a line break and set the merged
lines

## 2026-09-13 — One bad width, a whole book in fragments

The reader was indenting every line and pushing text to the next page. It looked
like a typography bug, and it was not: the paragraphs themselves were wrong.

`Dehyphenator.merge` joined a word split across a line break and set the merged
line's width to `prev.width + next.width` — 788pt on a 612pt page. Nothing caught
it, because a `Line`'s width is never read where it is written. It is read much
later by `ParagraphAssembler`, which took `body.maxOf { it.width }` as the measure
of the page. One hyphenated break was therefore enough to push the threshold above
every real line on that page, and a line that stops short of the measure ends a
paragraph. Affected pages came out one line per paragraph. The first-line indent
added the day before is what finally made it visible.

Both halves are fixed. A merged line now ends where its continuation ends, which is
the only right edge worth keeping — the head reached the margin by definition, or it
would not have been hyphenated. And the measure is the 90th percentile of line
widths rather than the maximum, so no single line can speak for a page again.

**What found it.** Not reading the code. I first concluded from a JVM diagnostic
that extraction was correct and the device data was stale, and that was wrong — the
instrumented probe reproduced the fragmentation exactly. Then, chasing an impossible
788pt width, I checked whether any *run* overflowed the page: none did, which meant
the width was manufactured downstream and left exactly one stage to look at.
Printing the numbers beat reasoning about them, again.

**One trap worth recording.** An instrumented test runs `:core` out of the
*installed app APK*, not the test APK. Reinstalling only `androidTest` after a
`:core` change silently tests the old code — which is what made the first
verification run look like the fix had done nothing.

**Measured on the real book.** 5655 blocks to 5055; mean paragraph 80 to 89
characters; 2% of blocks ending without terminal punctuation, and those are title-page
lines. Chapter 8 went from 121 one-line blocks to 99 paragraphs. 512 tests, 0 failures.

**Still open.** A book keeps whatever extraction it was imported with, so this fix did
not reach the copy already on the device; it had to be re-imported by hand. And there
is no way to delete a book from the library at all.

## 2026-09-13 — Highlights, real sharing, and a Back button that goes back

Six things, one plan (`2026-09-13-folio-highlights-sharing-navigation.md`), all eight
tasks ticked. 557 tests, 0 failures.

**Justified by default**, for everyone. Changing the Kotlin default would have reached
only new installs, leaving the setting on for new readers and off for everyone already
here — a split nobody can reproduce a bug report against. `MIGRATION_3_4` turns it on,
which is safe precisely because nothing in the app had ever asked the question: the
stored `false` was the old default, not an answer.

**The Library header** loses the two circular buttons that went where the nav pill
already goes.

**Back** now means "out of this". One reducer, `ui/nav/QuireBack.kt`, describes every
level, and `QuireRoot` holds the only `BackHandler`. Only the Library asks before
closing. The reducer is pure, so `QuireBackTest` can walk it from the deepest state and
assert it unwinds to a bare Library in a bounded number of presses — a rule that popped
nothing, or two things, shows up there rather than as a stuck screen. Making it the
single handler meant the Reader could no longer rely on its own back arrow to save the
place, so it now persists on dispose instead; no caller has to remember.

**Sharing** was a sheet that closed and did nothing, behind four glyphs standing in for
apps. The glyphs were generic for a good reason — a recognisable mark without the SDK
is a trademark problem — but generic *and* inert was worse than either. The four
destinations are now the four things that actually happen: Image, Text, Copy, Save. The
card is captured with `rememberGraphicsLayer()` from the composable already on screen
rather than drawn a second time into a bitmap, and the caption box is a real field
instead of a placeholder that promised an edit and then sent the passage without it.
Share reaches the reader, the bookmark list and a book's own page.

**Highlights.** Long-press takes the word under the finger, a drag extends it across
paragraphs, and an action bar offers Highlight, Share, Copy. A highlight is a bookmark
with an end — one table, because a bookmark is a highlight of no width, and two would
mean two lists and two places to forget one of them.

The arithmetic worth naming is `Selection.portionOf`. A selection is stated in chapter
coordinates; a page draws a *slice* of each block, so a highlight of characters 120–140
in a paragraph whose page starts at character 100 has to come out as 20–40. Get it
wrong and the highlight still appears, over the wrong words, looking like a rendering
glitch. Sixteen tests in `:core` pin it.

**The assumption the feature rests on** is that a background span cannot move a line
break. The paginator measures a chapter without knowing which parts of it a reader has
marked, so the drawn page carries spans the measured one never saw. That is only safe
because a background changes no metric — so `MeasureMatchesRenderTest` now asserts a
highlighted paragraph breaks on exactly the same characters as an unhighlighted one.
The day someone reaches for a bolder highlight or an underline, that test fails instead
of pages quietly losing their last line again.

**Support.** A Settings row opening `razorpay.me/@gajanansr` in the browser, and a line
naming who made this. The URL is a constant with its own test: a mistyped donation link
sends a reader's money to a stranger and nothing in the app ever looks wrong.

**Two traps recorded.**

`FileProvider` canonicalises paths, and on macOS Robolectric's temp directory arrives
through the `/var` → `/private/var` symlink, so the configured root never matches and
the uri assertion cannot pass. The file write is tested directly instead, and the real
risk — code and manifest disagreeing about the provider authority, which crashes at the
moment a reader taps Share — is tested by reading the authority back out of the merged
manifest.

Compose gave one genuinely hard problem: a long-press-drag has to extend a selection
while the same surface turns pages on a horizontal drag. Keying the page-turn detectors
on whether a selection is live, and putting the long-press detector first in the
modifier chain, is what makes both work.

**Found while verifying on device**, and fixed: the exit dialog was wearing Material's
default lavender, the one surface in the app ignoring the theme the reader chose. And a
saved bookmark read "38" — `currentPageSnippet` took the first non-blank block, which on
a real page is the running page number. It now prefers the first block carrying letters,
falling back to whatever was there, because a poor snippet still beats an empty one.

**Still open**, unchanged: a book keeps whatever extraction it was imported with, and
there is no way to delete a book from the library.

## 2026-09-13 — A mark of its own, and passages worth sharing

**The logo.** It was a serif "F" on a blue ground — at 48dp, any app beginning with F.
A *folio* is a leaf of a book: one sheet folded once, which is where both the word and
the book format come from. The mark is now that — two leaves meeting at a fold. Three
details carry it: the fold is narrowest at mid-height and opens towards head and tail
(a parallel slot reads as two rectangles; a taper reads as one creased sheet), the head
and tail edges wave because a leaf lying open is never flat, and the left leaf is the
same page colour held back, which is the gutter shadow rather than a second material.

`LauncherMarkTest` reads the drawable's own path data back and asserts every
coordinate sits inside Android's 66dp safe circle, bounding the curves by their control
points — conservative in the right direction, since a Bézier never leaves the convex
hull of its own points. An adaptive icon is masked differently by every launcher, so a
mark that strays outside is not wrong on the machine you built it on; it is wrong on one
phone in five, with a shaved corner nobody reports. The palette is unchanged:
`IcLauncherColorTest` ties those two literals to the app's own accent and page tokens.

**Sharing a passage.** Selecting text and tapping Share fired a plain-text intent, and
the card — the thing anyone would actually post — was reachable only from the bookmark
list, at the far end of the journey from where the passage was chosen. Both routes now
open the same sheet.

The card can be dressed six ways: the book's own cover, and Folio's five palettes,
resolved through `QuireColors.of` so the card is never a sixth palette invented for one
screen and a change to Night reaches it without anyone remembering.

`ShareCardStyleTest` checks every style against every cover swatch for WCAG contrast,
and it found something: white on the lightest of the six cover gradients measures
**4.24:1**, under the 4.5 a passage of text needs. Five books would have looked fine
and the sixth would not, and it would have shipped. A flat 22% scrim over the gradient
fixes every swatch at once. A test asserts the raw swatches still fail on their own, so
nobody deletes the scrim on the strength of one book that looked all right.

**The card exports at 2x.** Captured from the composable on screen, which is ~600px
wide — post that and the platform recompresses it again, turning a serif quote to mush.
Recording the layer at twice the size and drawing it back down by half leaves the
preview identical and re-rasterises the type rather than enlarging pixels. Verified:
1208×2148 off the device, sharp.

**The footer invites.** The card used to end on the tagline, which is lovely and tells a
stranger nothing they can act on. It now carries the wordmark and a call to action, in
one constant shared with the text share — so a passage posted as words and the same
passage posted as a picture say the same thing, and it becomes a store link in one edit.

566 tests, 0 failures.

**One to watch.** `.claude/worktrees/` is now gitignored. Subagent worktrees are
separate checkouts living inside the repo, and `git add -A` will happily commit them as
embedded repositories.

## 2026-09-13 — Ready to submit, except for the five things only a human has

One plan (`2026-09-13-folio-play-release.md`), seven tasks, all ticked. 568 tests,
0 failures. Nothing about the app's behaviour changed except one new Settings row.

**The build can sign a release, and the debug build still needs nothing.** Credentials
come from a gitignored `keystore.properties` or from four environment variables; if
any is missing the release signing config is *not created* and only `assembleRelease`
and `bundleRelease` fail, with the four names in the message. No debug-key fallback —
Play pins an application id's signing identity forever, and a debug-signed artifact
that reaches a tester track cannot be replaced by a properly signed one later.

Verified end to end with a throwaway keystore made outside the repo and deleted after:
`bundleRelease` refuses without credentials, signs with them, `jarsigner` reports
verified. 17 MB unminified against the 67 MB debug APK, because the bundle splits per
device.

**R8 stays off, deliberately.** It was tried: it builds clean and takes the bundle to
11 MB. What could not be tried is whether a shrunk Folio still imports a book, and
PdfBox-Android is exactly the library where that is not rhetorical — it resolves
fonts, CMaps and codecs by class name out of its own assets, and a wrongly shrunk
PdfBox does not crash, it returns an empty text layer. A PDF then imports
"successfully" as a book with no words in it. Six megabytes is not worth shipping that
untested on a free app with no ads. `app/proguard-rules.pro` is written and committed
with every keep rule Room, kotlinx.serialization, WorkManager and PdfBox need, so
turning it on is `-PfolioMinify=true` plus the seven-step device test in
`docs/release.md` §9.

**The listing lives in the repo**, in fastlane's layout, so it can be diffed rather
than living only in a web form. Title 27/30, short 73/80, full 3542/4000, changelog
470/500 — counted in characters, because the em dash in the title is one character and
three bytes and `wc -c` would have passed a title Play rejects.

**The copy claims only what the code does.** Worth recording, because "Where things
stand" at the top of this file is now out of date: **Folio does no OCR.** That pipeline
was removed and a scan is shown as its printed pages — `PageRasterizer`'s own comment
records it, and `PdfPipeline.process` says why. The listing says the same. It also
carries a "What Folio does not do" section naming the absence of sync, a bookstore,
DRM support, text-to-speech and a dictionary, because that is cheaper than the review
that says it for you.

**The two graphics are generated, not drawn.** `scripts/generate-play-graphics.sh`
renders the 512×512 icon and the 1024×500 feature graphic with Java2D on the JDK 21 the
build already requires — no ImageMagick, no imaging library. The curves are
transcribed from `ic_launcher_foreground.xml` and the colours come from
`ic_launcher_colors.xml`, so the store icon regenerates with the brand rather than
being the one copy still showing the old blue in two years. The mark is scaled to 58%
of the icon's width rather than the adaptive icon's 46%: that number exists for the
launcher's circular mask, and Play's square is never masked.

**A privacy policy that is checkable rather than promised.** It lists all five
permissions the release manifest actually merges — `WAKE_LOCK`, `FOREGROUND_SERVICE`,
`RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE` and Android's own
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — with why each is there, and explains why
a *missing* `INTERNET` permission is a stronger claim than a promise. That makes
`NoNetworkPermissionTest` load-bearing in a new way: the listing now states it
publicly, so the day it is weakened the store listing becomes a false claim rather
than merely an outdated one.

**Links are in one file.** `share/Links.kt`, beside `SupportLink`. The privacy policy,
website, source and contact addresses are all blank and marked FILL IN, because there
is no website yet and an app that opens the browser onto a 404 is worse than one with
no link. Settings offers a row only once a link is set and shows the short truth about
privacy until then. `QuireRelease.VERSION_NAME` moved out of the inline `"0.1.0"` in
the About row, and `LinksTest` reads `app/build.gradle.kts` back so the two cannot
drift at the first update — mutation-checked, it does fail when they do.

**Two things found while doing this, neither one Play's problem.**
`java_pid90925.hprof`, a 322 MB JVM heap dump, was committed at HEAD. It is removed
from the tree and `*.hprof` is ignored; purging it from history is a separate,
destructive decision and was left alone.

**Still needed, and none of it could be produced here:** an upload keystore, a public
URL for the privacy policy, a contact email, at least two phone screenshots (six are
specified, screen by screen, in the README under `images/phoneScreenshots/`), and the
Play Console questionnaires. `docs/release.md` is the ordered list.

**One risk worth reading before submitting**, in `docs/release.md` §11: the Settings
donation link is an external payment page, and Play's Payments policy has repeatedly
been the thing that catches apps out there. It is a decision, not a fix.

## 2026-09-13 — A shared passage arrives whole

Reported from a real phone: "when I select and share, or just share the page, it shares
just one line — the first line — and clips out everything else." Two separate causes,
both of which made a truncated passage look like a complete one.

**Sharing a page sent one paragraph.** It reused `currentPageSnippet`, which is
deliberately a single block: a bookmark row wants one recognisable line, not a wall of
text. Sharing wants the page. They are two questions, and now they are two properties —
`currentPageText` joins every block the page draws.

**The card cut at 180 characters and then closed the quotation mark.** That is the worse
of the two: a reader who chose three paragraphs got the first sentence, punctuated as
though that were the whole quotation, with nothing to say otherwise. `QuoteFit` now
steps the type down through four tiers as the passage grows, and only past 700
characters does anything get cut — at a word boundary, with the ellipsis *inside* the
quotation marks. Silently dropping somebody's chosen words is the worst failure this
feature can have, so `QuoteFitTest` states it: a passage inside the cap comes out byte
for byte, a cut is marked, and a cut never lands mid-word.

Two things found while checking it on the device: once a long passage took the weight,
`SpaceBetween` had no free space left to distribute and the quote ended up touching the
title above it (fixed with real padding rather than an arrangement), and a chapter whose
detected title is its own number rendered as a stray "1" on the card — the detection is
right, the card just has to say what the number means.

573 tests, 0 failures.

## 2026-09-13 — Two widgets, and the parts of one that a test can never see

`docs/superpowers/plans/2026-09-13-folio-widgets.md`, nine tasks, all ticked. 637
tests, 0 failures — 76 new, all JVM.

**Reading Streak** is the streak as a number, the day's minutes against the goal, and
the week as seven bars, which is the Library's habit card at arm's length. **Reading
Stats** is books finished, chapters finished, time read, and the book open now with
its progress. Four cells by two each, resizable both ways. `RemoteViews` and
`AppWidgetProvider`, no Glance and no new dependency.

**Everything a widget decides is decided in `widget/WidgetState.kt`,** which imports
nothing from Android. That is not a preference here, it is the only way any of this
gets tested: a widget is inflated by the launcher, in another process, and there is
no JVM seam anywhere near it. `habitWidget(snapshot)` and `statsWidget(snapshot)`
carry 24 tests between them — the plurals, the empty state, the broken streak, the
goal of zero nobody can reach and the division that would crash on someone's home
screen if they did. The providers below have no branch of their own.

**The honest-empty-state rule turned out to have two sides**, and naming them was the
real design work. Zero is a fact: a reader three books into their library really has
finished none, and showing them "0 Books" is true. Having no library at all is a
different thing, and gets the invitation instead. The two are separate states with
separate tests, because collapsing them would have made the second reader look like
the first.

**The palette had to be copied, and a copy drifts.** A widget cannot reach
`QuireTheme` — it is drawn in the launcher's process, and the row holding the
reader's chosen theme is in a database that process cannot open. A resource qualifier
is the only theming it gets, and it distinguishes exactly two things, so the widget
wears Paper in a light launcher and Night in a dark one. `WidgetColorTest` converts
the same OKLCH tokens through the same transform `QuireColors` uses and asserts the
six literals still match, the way `IcLauncherColorTest` already does for the launcher
icon. It also asserts the dark values are *not* the light ones, because a
`values-night` file in the wrong directory passes every other assertion and shows up
only as a white card in someone else's dark launcher.

**Refreshing on the clock is not refreshing.** The platform's floor is thirty
minutes. The habit widget keeps it as a backstop — midnight resets "minutes today"
and can break a streak with nobody touching anything — and the stats widget sets it
to zero, because no book is finished by the passage of time. What actually keeps them
current is `onDataChanged`, a callback both repositories take and neither understands:
recorded minutes, a changed goal, a finished book or chapter, a saved book, an opened
one, saved progress, a deletion. `QuireGraph` is the only place that connects that to
`QuireWidgets.refresh`, and it dispatches off the caller's thread — the Reader
persists from a main-thread coroutine, and asking the AppWidgetManager what is pinned
is a binder call.

**The trap in that**, and it very nearly landed: `onDataChanged` is a `() -> Unit`,
and `BookRepository(db, store) { clock }` passes the clock as a trailing lambda. Put
the callback last and Kotlin binds `{ clock }` to it — it compiles, because any
lambda coerces to `() -> Unit` — and several tests quietly start running on the wall
clock instead of their own. It sits before the clock parameter in both repositories,
with a comment saying why.

**Three things RemoteViews will not do**, each of which shaped a layout. It cannot
add a child, so the week is seven fixed ids and every state of the stats widget is
present in the layout and switched off. It rejects any class the platform has not
marked `@RemoteView`, which rules out the bare `<View>` a hairline divider would
normally be — so the day bars are `ImageView`s, tinted through `setColorFilter` and
`setImageAlpha`, the two remotable methods that let a four-minute day be drawn lighter
than a full one without a drawable per intensity. And it draws only a `ProgressBar`,
so the whole appearance of the progress bar lives in its progress drawable.

**Found in review, not by a test failing**: nothing joined `HabitWidgetProvider` to
the habit renderer. Every test called the renderers directly, so swapping the two
providers' bodies would have passed all of them and put the same widget on both home
screens. `views` is `internal` now and two tests close that loop. The broadcast around
it — `goAsync`, the coroutine, the graph — is still unreachable from a JVM test, and
is the one part of this that has to be checked on a phone.

**One deliberate departure.** `previewLayout` shows sample values — a five-day streak,
four books. It is the only place in Folio that shows a number nobody earned. The
picker is a catalogue and no home screen ever inflates those layouts, both files say
so in a comment, and an empty invitation there would make the picker useless. Flagged
rather than hidden: say the word and they become the empty state.

**What still needs a home screen.** The widget picker itself (previews, labels, and
whether the four-by-two default is the size it claims); the stats layout at exactly
two cells, which is ~80dp of content and the tightest thing here; that a tap on the
streak widget lands on the streak screen when Folio is already open, which is
`onNewIntent` and cannot be driven from Robolectric; the day bars at real widths; and
the dark launcher.

## 2026-09-13 — Widgets, and the one thing tests could not answer

Merged `agent/widgets`: a Reading Streak widget (flame, streak, minutes against the
goal, the week as bars on the Library's own intensity ramp) and a Reading Stats widget
(books, chapters, time read, and the open book's progress). RemoteViews, not Glance —
Glance would be a dependency and this needs none. Refresh is driven by an
`onDataChanged` callback the repositories call, not by the system's half-hour cadence,
so the widget moves when reading does. 660 tests, 0 failures.

**The gap the agent named, closed on a device.** `WidgetState` decides *what* a widget
says and 76 JVM tests cover it — plurals, a broken streak, an empty library, a met
goal. Whether those words *fit* is a different question, and only a real inflater with
real fonts can answer it. The stats widget places three tiles, a book title and a
progress bar in about 80dp of usable height at its declared 250×110dp minimum, and a
widget that overflows is not clipped tidily: the launcher simply cuts it, on whichever
phone has the tightest grid. `WidgetLayoutTest` inflates both layouts on device at the
declared minimum, at the narrowest resize a reader can drag them to, and at a full 4×2
cell, and asserts no descendant reaches past the bottom. All four pass.

**Still unverified, and worth saying plainly.** Dropping a widget on a home screen by
`adb` drag-and-drop does not work reliably — the Pixel launcher cancels the gesture —
so the live render, the `goAsync` broadcast path and a tap while Folio is already open
have been seen only in the picker and in tests, not on a home screen.

**One departure, deliberate and flagged by the agent.** The picker previews show sample
values — a 5-day streak, four books, an invented title. It is the only place in Folio
showing a number nobody earned, and it is conventional for a widget picker, which is a
product illustration rather than a claim about the reader. Worth knowing it is there.

## 2026-09-13 — Reminders that stay quiet on the days it matters

Plan: `docs/superpowers/plans/2026-09-13-folio-notifications.md`, branch
`agent/notifications`. All eight tasks ticked. **663 JVM tests, 0 failures.**

One notification a day, at a time the reader picks, in words taken from the book they
are actually mid-way through — and none at all on a day they have already read.

**The whole feature is a negative, and that decided the architecture.** Almost every
requirement here is a notification that must *not* appear: not on a day with reading
in it, not twice in one day, not at two in the morning, not after the switch was
turned off. A negative is invisible on a device — nothing happening looks exactly
like nothing happening for the wrong reason — so the entire product question lands in
`Reminders.decide(facts)`, a pure function with no Android imports, and 21 tests say
what silence means and why. The worker around it gathers facts, asks, and posts.

The rule that matters most is `minutesToday > 0`, not `goalMet`. Four minutes of a
ten-minute goal is still a day the reader read, and the only notification that fits a
partly-read day is one pointing out the shortfall — which is the nagging the feature
exists to avoid.

**Eleven hand-written lines**, four registers, rotated by epoch day. Three properties
are tested rather than reviewed, because copy decays the moment nobody re-reads it:

- **No invented numbers.** Every variant is rendered against facts whose title and
  chapter carry no digits, those two strings are stripped, and every remaining
  integer must be the goal, the percentage or the streak. A separate test proves
  `1984` and `Catch-22` survive intact — the ban is on numbers Folio made up, not on
  numbers the author wrote.
- **No guilt.** A word list, asserted: `broke`, `broken`, `lost`, `fail`, `missed`,
  `don't`, `should`, `last chance`, `hurry`, `at risk`, `behind`, and no `!`.
- **Always the reader's own book.** Every with-book line must contain the title.

**The bug that would actually have shipped.** The rule above reads
`reading_days.minutes`, and that number lags reality by a whole session: minutes are
written when a session *ends* — on leaving the Reader, or on the app backgrounding.
A reader who starts at 19:50 with a reminder set for 20:00 therefore still has zero
recorded minutes at 20:00, every check passes, and the phone buzzes in their hands
on the page they are looking at. The worst possible version of the one rule that
matters, and every test of that rule passed.

The fix is a second signal: `reading_progress.updatedAt`, which is written on every
page turn because that is where the reading position is saved. Less than ten minutes
since the last turn means the book is still open, and Folio says nothing. Ten rather
than the accumulator's two-minute idle timeout because the costs are not symmetric —
being generous costs at most a skipped evening, and costs nothing in practice, since
a reader who really did stop gets their minutes recorded the moment the session
flushes.

**Two traps found by tests rather than by reading.**

`"1 days" in text` is true of `"11 days running"`. The first plural test failed at
streak 11 and the assertion, not the copy, was wrong — it now matches `(\d+)\s+days\b`
and asserts the captured number *is* the streak, which is the thing that was meant.

And the first "off means off" test passed for the wrong reason. The worker correctly
said nothing after the switch was flipped, but the job enqueued by the previous run
was still pending — silence, with something of Folio's still waking the device on a
schedule the reader had cancelled. The worker now cancels its own unique work when it
finds reminders off, so a stale job takes itself out rather than waiting to be
cancelled again. Two locks: the UI cancels immediately, the decision refuses anyway.

**The scheduling risk worth recording.** Each run enqueues the next with `REPLACE`,
under the same unique work name it is itself running under. If replacing a running
job dropped its replacement, reminders would stop dead after the first one — silently,
on a real device, a day later. That is now driven for real under
`WorkManagerTestInitHelper` rather than assumed, because a worker built by hand never
collides with its own name and would have proved nothing.

No exact alarms: `SCHEDULE_EXACT_ALARM` is special-access and an offline reading app
has no business asking for it. The price is that delivery is approximate, and it is
paid deliberately — a three-hour window, clamped to the end of the day, outside which
the reminder is dropped rather than delivered stale. A phone that dozes all evening
and wakes at 02:00 says nothing, and does not record the day as reminded either, so
the evening the reader is actually awake for is still available.

**Permission is asked once, after the first session that recorded real minutes** —
never on first launch, where the question arrives before there is anything for it to
be about and gets the refusal it deserves. A refusal is permanent: Android stops
showing its dialog after the second decline and every later request returns "denied"
with nothing on screen, so after that the only route offered is Folio's own page in
system settings. Backing out of the offer counts as declining, and is recorded as
one.

`POST_NOTIFICATIONS` joins the reviewed allowlist in `NoNetworkPermissionTest`, with
a new test in the other direction: a runtime permission asked for but never declared
is refused instantly and silently, and the symptom is a feature that simply never
works with nothing in any log to say why. `INTERNET` is still removed.

Database at **version 6**. `MIGRATION_5_6` adds seven columns, and the one that
matters is `remindersEnabled DEFAULT 0`: an update that starts buzzing someone who
never asked is the worst possible introduction to this feature.

**Left out on purpose**: snooze (turns one notification into two), an import-finished
notification (the import is already on screen), per-book reminders, and a weekly
summary (a second notification whose job is to mention the first).

**A second bug of the same shape, and it took two passes to actually kill.** A book
read as pages keeps its page number in the *chapter* slot of the same
`reading_progress` row a reflowed book uses — `PagePosition` puts it there, and both
readers share one row per book. Read back as a chapter index it produced "Chapter 41"
for a reader on page 41: specific, confident, and false about something they can
check.

The first fix nulled the label when the stored index fell *outside* the chapter list.
That covers a scan, which carries no chapters at all — and misses the worse case
entirely. `reflowFailed` has two routes (`PdfPipeline` lines 68 and 104), and the
second is a reflow whose confidence was merely too low, which keeps a **real** chapter
list. Book Details offers "read the pages" for both. So on that route the page number
lands *inside* the list, resolves to a genuine chapter, and the reminder announces
chapter two's real title to someone sitting on page two. A range check cannot see it.
The guard is now `candidate.reflowFailed` asked *before* the lookup.

That one line is sufficient rather than lucky, and the argument runs from the other
end: `PagePosition.of` has exactly one call site in main (`QuireRoot`, inside the
`originalPdf != null` branch), that branch is reachable only through
`onReadOriginal`, and `BookDetailsScreen` offers `onReadOriginal` only under
`if (state.reflowFailed)`. So every page-unit write to `reading_progress` comes from
a `reflowFailed` book, and the guard is a strict superset of the cases that need it.
Worth writing down, because the next person to read that `when` will wonder whether
it is a special case or a rule.

`BookInProgress.chapterLabel` is nullable, and when there is no chapter to name the
copy falls back to the lines that never name one, so the book is still named.

**Two tests here were worthless when first written**, and both for the same reason.
The scanned-book test ran a single day — and more than half the copy variants never
mention a chapter anyway, so it passed or failed depending on the date. It now walks
six days, and was confirmed by reinstating the bug and watching it go red. The rule
holds generally: a test against copy chosen by a rotation has to walk the rotation.

**What a review caught that neither found.** `QuireRoot` holds the app's only
`BackHandler`, and the invitation's Back clause was keyed on the offer being *owed*
rather than *on screen*. Since a session flushes when the app backgrounds — which is
how most sessions end — the offer is routinely raised while the reader is still in
the Reader, where the invitation is not drawn. Back was therefore swallowed by a
screen nobody could see, and the one-shot offer recorded as answered: the reader
lost a Back press and lost reminders permanently, with nothing on screen to explain
either. The rule is now `invitationVisible(...)` in `ui/nav/QuireBack.kt`, read by
both the screen switch and the Back handler so they cannot disagree, with four tests.

The same review then caught that the first scanned-book fix was only half of one —
the low-confidence route above. Worth recording *why* two rounds were needed: both
misses were the same mistake, checking a proxy (is the index in range? is the offer
owed?) instead of the thing itself (is this book read by page? is the screen
visible?). A proxy that is true in every case you thought of is indistinguishable
from the real rule until the case you didn't.

Three more from the same review: the notification's `PendingIntent` had
`CLEAR_TOP` without `SINGLE_TOP`, which on a `standard` activity destroys and
recreates it — tapping "open at Chapter 9" would have landed the reader on the
Library; the worker rescheduled from a settings snapshot taken before it posted, so
a toggle-off landing mid-run left a job alive; and one notifier test asserted the
thing its name did not claim.

**Not verified on a device.** Everything above is JVM and Robolectric; the emulator
was in use. What still needs a real phone: the notification's appearance and the
`ic_book` small icon at status-bar size, the system permission dialog, the deep link
to notification settings, and — the one that cannot be simulated — whether Doze
actually delivers inside the three-hour window on a phone left alone overnight.

## 2026-09-13 — The widgets, made good

`agent/widget-redesign`, plan at
`docs/superpowers/plans/2026-09-13-folio-widget-redesign.md`, seven tasks, all ticked.
690 JVM tests, 0 failures; 106 of them are about the widgets now, up from 78. Nothing
either widget *says* changed — `WidgetStateTest`'s 24 tests were not edited — and
neither did the tap, the launch mode, `updatePeriodMillis` or the `onDataChanged`
refresh path.

**A widget wears the reader's theme now, not a guess at it.** The first plan said a
widget can only be themed by resource qualifier, so it got Paper in a light launcher
and Night in a dark one, and Sepia, E-ink and Black stayed inside the app. That was
true about *resources* and not about *colours*. The provider already opens the
database on every update — that is where the streak comes from — and `themeName` is
one column over from the numbers it was already reading. So it rides along in the
same single pass, through `themeNamed` rather than `valueOf` so that a reader who
chose "DARK" before the themes were renamed is not quietly reset, and
`widgetPalette(theme)` turns it into the six colours the views need.

**The technique, because minSdk is 26 and this is where it would have gone wrong.**
`RemoteViews.setColorStateList` is API 31 and unavailable. Text takes `setTextColor`,
which has been there since API 1. Everything that is a *shape* — the card, its
hairline, the seven day bars, the progress track and fill, the flame, the mark — is
a white drawable in an `ImageView`, tinted with `setInt(id, "setColorFilter", …)`:
SRC_ATOP keeps the drawable's alpha, so rounded corners stay rounded, and replaces
the colour. `setBackgroundColor` is the other thing that works on API 26 and paints
square corners, which is no use for any of them.

`setInt` does not call anything. It records a *method name* that the launcher looks
up by reflection and refuses unless it is annotated `@RemotableViewMethod` — so a
wrong name is a silent no-op on someone's home screen, with no exception and no log.
The three names live in one object and `RemotableCallTest` reads the annotations off
the real framework classes and fails if any of them is not remotable; a third test
greps `WidgetViews.kt` for `setInt` calls that bypass that object. That is the API 26
proof, since the annotation is the whole of the platform's own check.

**The progress bar stopped being a `ProgressBar`.** It had to: `setProgressTintList`
needs a `ColorStateList`, so a real bar cannot be recoloured before API 31 and would
have been wrong on three palettes out of five. It is two tinted `ImageView`s now, and
the fill is a `<clip>` drawable opened to the percentage with `setImageLevel` — which
is what a `ProgressBar`'s progress layer is anyway, so it looks the same. A clip
drawable at its default level draws *nothing*, so a missed call would show an empty
track and read as a book nobody had started; the test asserts the level that arrives
rather than that the call was made.

**The mark.** `folio_mark.xml`: the same folio the launcher draws — one sheet folded
once — redrawn on a 24 grid, in **one flat colour** rather than the launcher's two.
The launcher's shadowed leaf is an 0.8-alpha page over a fixed navy ground; a widget
has five grounds and that leaf is mud on Black and invisible on E-ink. It also opens
the fold: the launcher tapers it to under a unit at mid-height, which closes up
entirely at 16dp. It sits in the corner of both widgets in the muted colour — beside
the headline on the streak widget, where it balances the flame, and overlaid on the
stats widget, where a header row would have cost a fifth of the height and where
being a child of the root is what keeps it visible in the empty state.

**Compact, and using the full size.** Padding 16dp → 12dp. The streak widget's week
strip took a `layout_weight`: 30dp of bar at the declared 250×110 minimum, 80dp at a
full 4×2 cell, 100dp at the largest resize — everything above it is fixed, so every
pixel a reader adds by resizing goes to the data rather than to the margins. The
stats widget's empty invitation lost its inner panel and is simply centred on the
card; one themed surface is enough on a card 86dp tall, and `widget_panel.xml` went
with it.

**Every TextView has a fixed height and autosizes**, which is the part that made this
safe to do without a device. The height of a widget is now a sum of constants —
12 + 48 + 8 + week + 12 for the streak — so the budget is arithmetic rather than an
estimate, and at a 1.3× system font scale the text shrinks inside its slot instead of
pushing the week strip off the bottom of the card.

**Measured, not reasoned about.** `WidgetLayoutBudgetTest` inflates both layouts
under Robolectric at 250×110, 180×110, 330×160 and 360×180, in all five states either
widget can be in, and at 1.3× text — and it replays `WidgetLayoutTest`'s own
`contentBottom`, copied verbatim including the double-counting that makes it strict,
so the device test's answer is known before it is run. At the minimum the streak
widget's content ends at 98dp of 110 and the stats widget's at 95dp. Deliberately
breaking the layout was checked to break the test.

**Contrast, per theme, against that theme's own card** — the `ShareCardStyleTest`
method, which is what caught the share card at 4.24:1:

| | card | headline | secondary | accent |
|---|---|---:|---:|---:|
| Paper | #FDF9F6 | 16.6:1 | 4.65:1 | 8.1:1 |
| Sepia | #F2E6D3 | 11.1:1 | 4.90:1 | 6.2:1 |
| E-ink | #E9E9E9 | 15.2:1 | 4.95:1 | 13.1:1 |
| Night | #1B1C1E | 11.2:1 | 4.67:1 | 6.9:1 |
| Black | #000000 | 12.0:1 | 4.89:1 | 7.9:1 |

Floors are 4.5:1 for text and 3:1 for the secondary line and for the graphics that
carry meaning. E-ink's six widget colours are asserted to be strictly neutral, which
is the property that theme exists for.

**One design decision changed a colour rather than a rule.** The unlit flame was the
border colour, the same as an unread day bar. On Night that is four steps from the
card and reads as a drawing that failed to load rather than as "not yet" — seven
faint bars read as an empty week, one faint icon reads as a bug. It is muted now.
`lit` still means accent and unlit still does not, so the rule and its test are
intact.

**What still needs a home screen**, and none of it has been seen on one:

- That `setImageLevel` really does reach the clip drawable through a launcher's
  reflection on an old device. It is asserted under Robolectric and the method is
  annotated, which is the platform's own test, but API 26 itself is unverified.
- The two full-bleed `ImageView`s behind the content, against the system corner
  radius on Android 12+, where the launcher clips the card a second time.
- The mark at 16dp on a real screen, on all five themes.
- The week strip at 100dp on the largest resize — seven tall columns is the one place
  this could look like a chart nobody asked for.
- The first frame from `initialLayout`, which is the only thing the static
  Paper/Night resources are still for.
- The picker previews, which were rewritten to match the new layouts.

## 2026-09-13 — Open source, and a widget tap that stopped relaunching the app

**Folio is public: https://github.com/gajanansr/folio** — MIT, CI green on the first
run, 792 tests.

**A widget tap was starting a second copy of the app.** Reproduced before fixing:
tapping three times left three `MainActivity` instances stacked on each other, so the
app appeared to relaunch and Back peeled the copies off one at a time. The intent
carried `FLAG_ACTIVITY_SINGLE_TOP`, which reads like it prevents exactly this and does
not — it reuses the activity only when it is already the top of the target task, and a
launcher starting one with `NEW_TASK` usually is not. The guarantee has to come from
the manifest: `launchMode="singleTask"`. `WidgetLaunchTest` reads it back rather than
trusting the flags. Measured again after: flat at one instance across three taps.

**Publishing needed history rewritten, for two reasons.** The 322 MB heap dump
committed on 11 September is over GitHub's 100 MB hard limit, so the push would simply
have been rejected — the tidy-up I had flagged as optional turned out to be a blocker.
And 62 of the commits carried a work email that a public repo would have made permanent.
`git-filter-repo` handled both in one pass. Dry-run on a `--no-local` clone first, and
the check that mattered was comparing the HEAD *tree hash* before and after: identical,
so every file at HEAD survived byte for byte and only the blob's presence in older
commits changed. 128 commits in, 128 out. 113 MB to 6.4 MB.

**`scripts/env.sh` exported a macOS Homebrew layout unconditionally**, which made the
build machine-specific and would have failed for every contributor and for CI. Each
path is now applied only when it exists. The green CI run on a clean Ubuntu runner is
the proof, and is worth more than the assertion.

**Four agents' work merged**: notifications, widgets, the widget redesign, and Play
release readiness. Every merge conflict in this round was additive — two branches adding
different things in the same place — except one: concatenating both sides of a conflict
inside `MainActivity` swallowed a closing brace, because the `}` that ended one side's
method sat on the far side of the `=======`. The compiler caught it; a careless
resolution of an "additive" conflict is not automatically safe.

**Still open:** a book keeps whatever extraction it was imported with, there is no way
to delete a book, and a scanned PDF with no text layer still takes its title from the
filename. The pre-rewrite repository is at `/tmp/folio-backup-prerewrite` until the
next reboot, if the old history is ever wanted.

## 2026-09-13 — A landing page, and a privacy policy with a URL

**https://gajanansr.github.io/folio/** — a static page in `site/`, deployed by a
workflow on every push that touches it. No fonts, no analytics, no CDN, nothing loaded
from anywhere else. That is not a performance decision: Folio's whole claim is that it
makes no network requests, and a page that quietly called three companies to say so
would be the wrong way to say it.

**The privacy policy is generated, not copied.** Google Play needs it at a public URL
and the repository should carry the document it ships, and two hand-maintained copies
of a legal text is exactly how a stale privacy policy gets published.
`scripts/build-site.py` renders `docs/privacy-policy.md` into `site/privacy.html`, the
deploy workflow re-renders it and fails if the committed copy disagrees, and the
script refuses to build at all if a `[CONTACT EMAIL]`-style placeholder survives — a
placeholder that reaches a published policy is worse than a failed build, because
nobody re-reads a privacy policy after the first time they host it. This closes one of
the five things only a human could supply; the contact route is now the public issue
tracker.

**`QuireLinks` is filled in.** `PRIVACY_POLICY`, `WEBSITE` and `SOURCE` are real.
`CONTACT_EMAIL` stays deliberately blank: Play's listing needs a real mailbox and that
is not something to invent.

**A flash of onboarding on every cold start, found while capturing screenshots.**
`QuireRoot` collected settings with `initial = AppSettingsEntity()`, whose `onboarded`
is false — so for the frame or two before the database answered, a reader who had used
Folio for months was greeted with "A quiet place to read / Get Started". The honest
state before an answer is "not known yet", and the screen for that is nothing at all.

**Two things verified on the device that only the device could answer.** The widget on
a real home screen followed a theme change instantly — which is the one-line refresh
fix proving itself — and the live site renders correctly in a phone browser.

Screenshots for the site and the Play listing are in `docs/screenshots/`.

## 2026-09-14 — Folio is now Quire

Folio is a crowded name — a book-streaming app already holds *Octavo* on Play, three
separate apps hold *Dogear* (one pitched as "private book tracking, no ads, no social
feeds, no algorithms", which is this app's positioning word for word), and *Lectern* is
taken by "a calm ebook & audiobook reader for Android". Verso and Quarto both belong to
real publishers, which is the one industry where a trademark actually bites.

**A quire is a gathering of folded sheets** — nested, stitched at the fold, the unit a
book is physically built from. It is the direct sibling of *folio*, which is one such
sheet, so **the mark did not have to change at all**: the icon already draws the fold.
The comments explaining it did change, because the story behind a logo should match the
name above it.

**The timing was the whole point.** An `applicationId` can never change after a first
publish. Nothing had shipped, so this was the last moment the name was free to move.

Renamed: the application id and every package, every `Folio*` type and the files that
held them, the app label, the wordmark, the site, the Play listing, the repository, and
the Gradle properties. **Deliberately left alone:** the five comments that use *folio*
to mean a folded sheet. That is the correct word for what they describe, and a quire is
made of them — a blind find-and-replace would have turned accurate typography into
nonsense. Capitalisation separated the two cleanly: the brand was always `Folio`, the
term always `folio`.

**Two things the rename caught.**

`sed -E` on macOS does not understand `\b`, so the pass that renamed every `Folio*`
type silently did nothing and reported success. Only grepping for what should no longer
exist revealed it. BSD sed is not GNU sed, and a substitution that matches nothing
exits 0.

And the cached EPUB fixture still said "Folio Test Press" after the generator said
"Quire" — the staleness trap already recorded in this log, caught this time by the
gate rather than by a confusing afternoon. `FIXTURE_VERSION` is 6.

**793 tests, 0 failures.** Site live at https://gajanansr.github.io/quire/, repository
at https://github.com/gajanansr/quire (GitHub redirects the old URLs). Verified on the
device: installs as a fresh package, onboards from scratch, icon and label correct.

## 2026-09-15 — The picture that was sending text

Reported from a real phone: *"on sharing the image is not being shared, the text is
being shared. that image being formed is too much zoomed and not at all responsive.
font size should be less and same font that is selected while reading."* Three separate
faults behind one sentence, one plan
(`2026-09-15-quire-share-card.md`), five tasks.

**The image share was an image and a piece of text, and the receiver chose.** The
intent carried `EXTRA_STREAM` *and* `EXTRA_TEXT` whenever there was a caption. An
`ACTION_SEND` holding both is ambiguous by construction: the system Sharesheet builds
its preview from `EXTRA_TEXT` before it ever looks at the stream, and an app that
registers one `ACTION_SEND` handler for text and for images commonly reads `EXTRA_TEXT`
and never opens the stream at all. The picture was in the envelope the whole time.

**The rule now, and `SharingTest` states it: an image intent never carries
`EXTRA_TEXT`.** The uri travels in `EXTRA_STREAM` *and* in an explicit `ClipData`,
because receivers read one or the other and the sender does not get to know which; the
caption rides on that clip's item and nowhere else. Building the clip ourselves is also
what holds the rule — `Intent.migrateExtraStreamToClipData` synthesises one from
`EXTRA_STREAM` and `EXTRA_TEXT` on the way out of the process, and it bails the moment a
clip is already set. The authority is now pinned to the literal
`app.quire.android.shares` as well as to the manifest, since code and manifest both
derive from `applicationId` and would agree with each other even if the id were wrong.

**"Too zoomed" was a unit problem, not a taste problem.** Every measurement on the card
was absolute dp or sp, picked by eye against the sheet's ~230dp preview. The export is
604px and is looked at full-bleed — about 1.8x what it was tuned at — and nothing in the
card knew that. `CardMetrics` now holds the card as fractions of its own width, so the
preview is a true scale model of the export rather than a smaller, different card.

The old tiers were wrong on their own terms too, and the new tests say so with numbers:
23sp on a 230dp card is **17.3 characters to a line**, and a 231-character passage
**needed 12 lines and was given 11** — silent truncation arriving by the back door,
past the cap that exists to make truncation visible. A `QuoteFit` tier now names a
*measure* (22/26/32/40/46 characters to a line) and both the size and the line budget
fall out of the card's real width. Every length from 1 to the 700-character cap is swept
for both invariants. Sizes at the preview: 18.1 / 15.3 / 12.4 / 9.9 / 8.7sp, every one
smaller than the 23 / 19 / 16 / 13 it replaces.

**Both ends of the range now look composed.** The passage sits in a weighted box,
optically centred between the header and the footer: a six-word quote is centred in its
own field with air either side, a six-hundred-character one fills it. `SpaceBetween`
alone hung the short passage under the title with all the emptiness below it. Type
sizes are pinned against the system font scale — a card is a picture with fixed
proportions and no way to re-flow once it is a PNG.

**The card is set in the reader's own face.** `ShareCard.Quote` carries the
`ReaderFont`; `ReaderHost` fills it from `state.preferences.font` and `QuireRoot` did
not have to change. The wordmark stays Source Serif on purpose — that is Quire's mark,
and a brand line that changes typeface with a preference is not a brand line. The slant
follows the face: Source Serif and the platform family ship real italics, Lora and Work
Sans do not, and Compose fakes one by shearing the upright, which at card sizes reads as
a rendering fault rather than a quotation.

**One found while checking Save.** `saveToPictures` inserted the `MediaStore` row before
writing a byte and never took it back out on failure — a zero-byte picture that opens as
a grey square, *and* a fallback to the chooser, so the reader ends up with the card saved
twice and one of the two broken. The write can also throw rather than return null (a
full volume, an unmounted card), which was an uncaught crash on the tap of a button.

**807 tests, 0 failures.** No new dependency, no Compose UI test, `INTERNET` still
absent. What a receiving app does with a well-formed intent is not something the JVM can
assert — that part is verified on the device.

## 2026-09-15 — The reading habit: a clock, a forgiving streak, a quote a day, a guide

Plan: `docs/superpowers/plans/2026-09-15-quire-habits.md`, branch `agent/habits`.
All five tasks ticked. **864 JVM tests, 0 failures** (from 793).

**The streak now forgives a missed day, and does not lie about it.** This was the one
decision here that needed research rather than engineering, so the plan carries the
survey — Duolingo's freezes and gem-priced repair, Apple Fitness's total absence of
forgiveness and the documented ring guilt it produces, Oura's rolling seven-day window
where rest is *scored positively*, Headspace, Snapchat's monetised restore and the
peer-reviewed literature on adolescents sending black rectangles to keep a streak
alive, and the weekly streaks Strava and Nike Run Club use.

The model: **a run survives one missed day; two rest days must be seven days apart;
the number counts only days the reader actually read.** A run of thirty that carried
two rest days says thirty, not thirty-two — strictly more honest than every freeze
mechanic surveyed and than Streaks for iOS, the closest comparable, both of which
inflate the headline integer by the days they forgive.

The property that keeps it from becoming pressure is that **there is no object**.
No freeze to earn, spend, equip or lose; the allowance is a property of the calendar,
so there is never a state in which the reader is holding something they might waste.
It is Oura's rolling window wearing a streak's clothes.

The rest days come back from the same function that computes the run, so the number,
the sentence under it and the heatmap all describe one thing. A rest day is drawn as
the empty square it was, outlined — filling it in would be the streak-freeze mistake.

**A weekly streak was the survey's own first recommendation and was rejected**, which
is worth recording. Strava and Nike Run Club are right that a week is the shortest
period over which missing is almost always a choice. But Quire has shipped a streak
counted in days: the Library card, the streak screen, the share card, both widgets and
the notification copy all say "day". Migrating to weeks would restate every reader's
history in a new unit and give a new reader nothing for six days.

**Copy that claimed consecutive calendar days is gone, with two tests behind it.**
"Three days running", "one after another", "you've read every day this week" are all
false for a run that carried a rest day — warm, specific, confident and wrong, which
is the failure the honesty rules exist to prevent, and the harder one to notice
because the *number* is right. A second test bans any mention of the mechanic itself:
no rest day offered, spent or endangered. Silverman & Barasch (JCR 49(6)) found that
pointing at a break accelerates abandonment, so the correct number of streak-anxiety
notifications is zero.

**Eleven quotations, all verified against primary texts.** Three famous ones did not
survive verification and are not shipped: "a room without books is like a body without
a soul" traces to an 1864 *Blackwood's* reviewer paraphrasing a biographer who had
mistranslated Cicero's *mens* as *soul*; the Mark Twain one first appears as unsigned
advertising copy in 1914 and was attributed to him in 1945, thirty-five years after he
died; "once you learn to read, you will be forever free" does not occur in Douglass's
*Narrative* at all and inverts what he wrote about literacy.

**The trap nobody expects is translation copyright.** An author dead four hundred
years does not make a modern English rendering free — a translation is its own
literary work with its own term. The Kafka everyone quotes is the Winstons, 1977; the
famous Proust is Autret and Burford, 1971. Both are in copyright and neither is here.
Montaigne is Cotton/Hazlitt, not Frame; Cicero is Shuckburgh 1899, and says a garden
*in* your library, which is his joke — the popular "a garden and a library" inverts
the grammar.

**Migration 6 → 7** adds `guideSeen`, seeded `UPDATE app_settings SET guideSeen =
onboarded`. That line is the whole migration: the column's Kotlin default is `false`,
which is right for a fresh install and catastrophic for an upgrade — every reader who
has had Quire for months would be greeted by a three-page tour on the launch after an
update they did not ask for. Tested raw-SQLite, as `SettingsMigrationTest` does,
because `exportSchema = false`. The version-5 chain test now runs 5 → 6 → 7: Room
validates once, at the end, and a device two versions behind is exactly the one nobody
tests by hand.

**The clock does not use `TimePickerDialog`.** Material3's own dialog draws its title,
mode toggle and buttons from `MaterialTheme`, so the frame would arrive in the
platform's colours even with the dial corrected — the lavender `AlertDialog` mistake
again, one layer out. A plain `Dialog` on Quire's own surface is less code. All
fourteen `TimePickerColors` come from `QuirePalettes`, held against the palette rather
than against literals, and the *count* of fourteen is asserted too: Material fills any
colour you do not supply from its own scheme, silently, so a colour added in a later
Compose release would otherwise be one purple ring on a sepia page.

**The daily goal is any number of minutes from 1 to 120.** One because `metGoal` is
false when the goal is zero, so a zero goal is a streak that can never start; a minute
is also the smallest total the accumulator records. Two hours because a daily goal
here is a floor to clear rather than a target to fail. `setDailyGoal` clamps instead of
throwing — with a stepper behind it, the old `require` would be an uncaught exception
in a coroutine launched from a composable, which is a crash on a tap.

**One existing test was replaced rather than weakened.** `only the handoff's four goals
are accepted` asserted precisely the behaviour this work exists to remove. Its
replacement is stronger: every minute in range stored exactly, out of range clamped,
and — the property that makes a freely settable goal safe — a goal moved to either
extreme still cannot rewrite whether a past day met the goal in force when it happened.

**A test found a real copy problem.** The guide's first page said "nothing to subscribe
to" and the no-false-promises word list tripped on it. That was the right outcome
twice: denying a feature in its own marketing vocabulary still puts the feature in the
reader's head. It now says "nothing to join".

**What still needs a real phone.** The clock dial's appearance in all five themes —
the contrast is asserted but the *look* is not; the rotating dial gesture; the guide
on a genuinely fresh install; and the reminder's expanded notification, where the
quotation lives.

## 2026-09-15 — Large books, and a chapter title printed twice

Plan: `docs/superpowers/plans/2026-09-15-quire-large-books.md`. Two reports from a
real phone: *"large books font resize and initially page change and all doesnt work at
all after some time settles"*, and *"part 1 part 1 comes twice"*.

**The quadratic came back, in blocks instead of characters.** `ChapterOpening`
answered "does a chapter open here?" with `blocks.take(index).none { it is Paragraph }`
and `Paginator` asked it once per block, so block *i* copied and scanned *i* blocks.
Counted rather than guessed — a `List<ContentBlock>` that records every `get`, in the
spirit of the character counter written for the last one:

| blocks in the chapter | block reads | per block |
|---|---|---|
| 500 | 127,301 | 254 |
| 1,000 | 504,604 | 504 |
| 2,000 | 2,009,209 | 1,004 |
| 4,000 | 8,018,420 | 2,004 |

Exactly N/2 + 4, the same doubling signature as the 2026-09-12 substring bug, reached
by the same books: the one-chapter TXT and the reflowed PDF are where N gets large.
**Four reads per block afterwards, flat.** The same function also rebuilt the block's
whole text to ask whether it was blank — `plainText` again, on a path `ReaderScreen`
runs per drawn slice per recomposition.

In milliseconds, on a warmed JVM with a trivial measurer, so this is the *scan* alone
and sits on top of whatever real text measurement costs: 0.7 → 0.4 at 500 blocks,
2.0 → 0.8 at 1,000, 6.3 → 1.5 at 2,000, and **20.0 → 1.8 at 4,000**. The curve is the
point rather than the numbers.

**Three scheduling faults, one symptom.**

*The cache could never hit.* The first-page header inset was taken from the open
state, whose page index is wherever the reader is standing, and `showsChapterHeader` is
false on every page but the first. So the inset was the header's full height on page
one and zero everywhere else — two keys per chapter in a three-entry cache, and every
repagination after the first laid the chapter out again. Worse than the wasted work:
a type-size change made on page seven laid the chapter out with **no room for the
header the renderer then drew on page one**, and the bottom of that page was clipped
away. That is the failure `MeasureMatchesRenderTest` exists to catch, arrived at
through scheduling rather than through a style, which is why the test did not.

*Pagination raced the reader's own typography.* The viewport is reported as soon as the
page is laid out; the saved font and size are a suspending database read. Losing that
race laid a long chapter out at the default 19sp serif and then drew it at the saved
22sp Lora. Nothing repaginated it — so it stayed wrong until the reader touched the
type stepper themselves, which is what "after some time settles" actually was.

*Nothing cancelled anything.* `applyPreferences` paginated in a coroutine of its own,
so four taps on the size stepper left four layouts of the same chapter live at once,
racing to write the state: the size that stuck was whichever finished last, not
whichever was asked for. And cancelling would not have helped — `paginate` is an
ordinary function inside `withContext`, so the coroutine dies and the work carries on.

There is one `LaunchedEffect` now, keyed on the viewport and the typography, so Compose
cancels the run it replaces; `ReaderLayout.requestFor` returns one value carrying the
inset *and* the cache key so the two cannot be built differently at two sites; and
`paginate` takes an `isActive` probe checked once per page. It throws rather than
returning the pages so far, because a short page list is indistinguishable from a short
chapter and would be cached — and a reading position resolved against it would put the
reader somewhere they have never been.

**And every tap during pagination vanished.** `nextPage` on an empty page list reports
that this is the last page; `turn` read that as a chapter boundary and abandoned it,
because the chapter count had not loaded either. On a book small enough, pagination
finishes before a finger can land. On a large one it is seconds of taps doing nothing.
`ReaderState.pendingTurns` remembers them and applies them when the pages arrive,
clamped inside the chapter: a tap made while the reader could not see what they were
turning is not evidence that they wanted the next chapter.

**Part 1, Part 1.** `showsChapterHeader` required `blocks.first()` to be a `Heading`
whose trimmed text equalled the trimmed title, case-insensitively. Real books defeat
every clause, and eight tests over real chapter shapes fail against that rule: a page
break, an empty block or a running page number ahead of the heading; a title set as a
styled paragraph rather than an `<h1>`; `Part&nbsp;1` against `Part 1`; a doubled space
from a line break inside the tag; a trailing full stop; a soft hyphen; and an untitled
chapter whose own heading says the "Chapter N" the header would draw.

`ChapterHeading` finds the first of the leading blocks that carries a letter — which is
what steps over the break, the blank and the page number — and compares letters and
digits only, case-folded, walking both strings rather than reducing them to two new
ones. That last part matters: this is asked on every recomposition and the opening
block can be the whole book.

**The line that must not move:** a heading that genuinely differs from the title is a
real chapter title, and hiding its header loses it. The comparison is an equality, and
six negative tests hold it — a subtitle, a differing heading, a paragraph that merely
begins with the title, and a chapter whose heading is only the label while it has a
title of its own.

**Three more the review found, two of them sharper than anything above.**

*Pages for one chapter could be written onto another.* The repagination effect captures
its chapter before it suspends and re-reads the state after, and the Contents sheet
loads a chapter in a coroutine that effect does not cancel. Pick a new chapter while a
long one is still laying out and the state ends up describing chapter 10 with chapter
3's page breaks — so `position` is a character offset from one chapter stamped with the
other's index, and `onDispose` writes it to the progress row. The book then reopens
somewhere the reader has never been. `repaginated` now takes the chapter the pages were
laid out *for* and discards them when it is not the one in hand. The guard lives in the
transition, not at the call site, because there is no call site that may skip it.

*The header inset under-budgeted, and clipped the page it heads.* The estimate was a
multiple of the **body** line height, but the header is drawn at fixed theme sizes —
`labelSmall` at 14sp and `headlineLarge` at 34sp — plus two gaps in dp. So it shrank
exactly when the reader chose small type: at 15sp it came up eight points short of a
two-line title, and three-line titles were short at 19sp too. Carried over unchanged
from before this work, but this is where it became reachable, because before the fix
above the inset was usually wrongly zero, which is worse. It is now arithmetic over
what `ReaderScreen` draws, term for term, with the title's line count the only guess —
rounded up, floored at two, capped at four, because one line too many is whitespace and
one line too few is a sentence cut in half.

*A turn queued during pagination followed the reader into a chapter they chose.* Tap
three times waiting for the book, then pick a chapter from Contents, and you landed on
its page three. Queued turns are now honoured only when opening *at* a position, which
is the book resuming — a chapter opened with no position is a deliberate jump.

Also from the review: the loading screen drew "Chapter 1" above a book being resumed in
chapter 12, because the header answered "yes" for a chapter that did not exist yet —
and waiting for the typography lengthened that window. And finding where a chapter opens
is now hoisted out of the render loop as well as the paginator's.

The header's measurements are now pinned against `QuireTypography` by a test, so
restyling the header fails the gate instead of quietly clipping the page it heads —
which is the same trick `BlockStyles` plays for every other kind of block.

One finding was declined. `typographyLoaded` has no failure path, and wrapping the
settings read in `finally` would set it on *cancellation* too — paginating the book at
default typography precisely when the effect was being torn down. Degrading properly
would mean swallowing a database error, which this codebase does not do.

**What a device still has to answer.** Every fault above was fixed against a property a
JVM test can state; none of them was fixed against a stopwatch, because there is no
phone here. The 276-page book is the check.

Gates: 849 JVM tests (357 `:core` + 492 `:app`), 0 failures. 56 added.

## 2026-09-15 — Selection you can actually grab, and brightness on the right edge

Two complaints from the phone, both in the Reader's gesture layer: *"the text
selection doesnt work well like it works on other apps"* and *"scroll down on right
side should adjust the brightness"*.

**The largest cause of the first was not selection at all — it was arithmetic.** A
drawn block registers `positionInRoot()`. A touch arrives in the coordinates of the
composable that caught it. Those are not the same space: `QuireRoot` wraps the whole
app in a `statusBarsPadding()`, so the Reader's surface begins a status bar below the
root, and every touch was compared against text positions 70–140px taller than
itself. A long press took a word one to three lines **above** the finger. No amount
of handles rescues a hit test aiming at the wrong line, so this went first.
`PageTextMap` now holds the page's own origin and states every answer in the page's
coordinates.

**What selection does now.** Two teardrop handles with a caret bar at each end, drawn
in an overlay so they change no text metric — `MeasureMatchesRenderTest` stays true
with a selection open. Either handle can be grabbed after the finger lifts and
dragged character by character; dragging one past the other swaps which end is held
rather than stopping dead; a drag onto the anchor is refused, because an empty span
makes `hasSelection` false and would take the handles off screen mid-gesture. The
press-and-drag sweep works in whole words in the direction of travel, and keeps the
pressed word — it used to anchor on that word's *start*, so sweeping backwards
dropped the word under the finger out of its own selection. A tap on the chosen words
keeps them; only a tap elsewhere clears. The action bar moves to the top of the page
when the passage is low on it, instead of sitting on the words it offers to copy. A
haptic tick per character is the "which character am I on" signal, chosen over a
magnifier: `Modifier.magnifier` is API 28+ and its failure mode is a lens in the
wrong place, which reads as more broken than none at all.

**Four gestures, disambiguated by mechanism rather than by luck.** Tap, page-turn
drag and brightness drag were three pointer-input modifiers each guessing on its own;
they are one `awaitEachGesture` loop that classifies the drag once, from the first
movement past touch slop, and then holds that classification — re-deciding every
frame makes a diagonal drag flicker between turning and dimming. Ties go to the page
turn, which is the commoner intent and the recoverable one. Handle drags are innermost
and consume, which is the whole of their disambiguation.

**The long press is the one detector that cannot judge for itself, and I got that
backwards.** Three comments and a plan bullet said Compose's long-press detector
self-cancels past touch slop. It does not. `awaitLongPressOrCancellation` in
foundation 1.12.1 watches only consumption, out-of-bounds and pointer-up — I
disassembled it rather than argue from memory — and its timer is wall-clock, so it
expires happily under a finger that has been sweeping for half a second. Nothing
consumed a page-turn drag, so **a slow swipe popped a selection under the thumb
mid-swipe and swallowed the page turn**. In practice Android apps are saved from this
by `draggable` consuming past slop; this hand-rolled loop had to do the same. It now
consumes the moment it has classified anything at all, `DragIntent.NONE` included.

A wrong sentence that reads as a mechanism is worse than an admitted guess: it stops
anyone checking. Recorded in full in the plan.

**Three more conflicts of the same family.** A long press on the right edge swept the
passage *and* dimmed the screen, since the two look identical to anything watching
only movement — a sweep in progress now stands the drag loop down, because a long
press has already declared itself by being held. A press held past the timeout is no
longer also a tap, so a long press cannot immediately clear the selection it just
made. And both hand-rolled loops now clean up in a `finally`: changing a pointer
input's keys resets it by throwing straight through the block, so a repagination
while a handle was held left the "a handle is held" flag set and **disabled
long-press selection for the rest of the session**, silently.

**Brightness.** A vertical drag on the right fifth moves the Reader window's own
`screenBrightness`. The system setting is never written — that would need
`WRITE_SETTINGS` and would follow the reader into every other app on the device.
**Floor at 5%**, because a screen dragged to black hides the gesture that would undo
it along with the back button and everything else. Seeded from the system reading on
the first drag so it does not jump, held for the session, and restored to
`BRIGHTNESS_OVERRIDE_NONE` on dispose — by any route out, including a system Back
press, because tying the restore to the exit handlers makes every new way out a new
way to leave the screen dimmed.

**Not persisted, on purpose.** Brightness is environmental, not preferential: the
value that is right in bed at midnight is wrong on a train at noon, so a restored
value is wrong most of the times it would be restored, and its failure mode is the
worst one available — opening a book in daylight onto a screen dimmed for a dark
room, with the cure a gesture the reader cannot see to make. No settings column, so
no Room migration, which also keeps out of the way of the schema work running in
parallel.

**And it is held in a plain `remember`, because `rememberSaveable` was quietly the
persistence this decision rules out.** A rotation destroys the activity; `QuireRoot`
holds the open book in a plain `remember`, so the reader comes back to the Library and
the Reader never composes to consume the saved value. `SaveableStateRegistryImpl`
re-saves an unconsumed entry on every save after that, so it lives on — and the next
book opened, hours later, would have inherited a level chosen in another room. The
exact failure the paragraph above says the design avoids, arrived at by the back door.

**One more stale-state bug, found in the same pass.** `PageTextMap` was keyed on the
page *number*. A typography change re-pages and lands the reader on the same index
more often than not — always on a chapter's first page — so the map was reused with
rows for blocks that had fallen off the page. A press low on the page could resolve
into a block that was no longer on screen, light up, and **save a highlight against
text the reader never touched**. Keyed on the page itself now.

**860 JVM tests, 0 failures** — 67 new: 11 in `:core` for the selection model, and 56
in `:app` across handle geometry, reader transitions, the brightness ramp and gesture
classification. No new dependencies, no version bumps, no manifest change.

## 2026-09-15 — A chapter is not a chunk

`agent/chunked-pagination`, plan at
`docs/superpowers/plans/2026-09-15-quire-chunked-pagination.md`, eight tasks, all
ticked. **1,071 JVM tests (418 `:core` + 653 `:app`), 0 failures**, 64 added.

*The Love Hypothesis*, a 315-page PDF, imports as **one chapter: 8,621 blocks,
565,896 characters**. Its outline is unusable, so `ChapterDetector` correctly falls
back to `single` — and pagination then laid the whole chapter out, on open and again
on every tap of A+. The 2026-09-12 and 2026-09-15 fixes made that work *linear*; they
could not make it *small*, because the unit was the chapter.

So the unit is no longer the chapter. **A chapter is semantic** — what a reader picks
from Contents, from the book's own outline or not at all; `ChapterDetector` is not
touched and no heuristic returns. **A chunk is mechanical** — how much text is laid
out at once, and the reader must never be able to tell one exists.

**What it costs now.** `ChunkedPaginationCostTest` counts measured characters rather
than milliseconds, as `PaginationCostTest` does, on a chapter of the real book's shape:

| type | whole chapter | a window | |
|---|---:|---:|---:|
| 15sp | 560,365 | 20,812 | **27x** |
| 19sp | 607,035 | 16,877 | **36x** |
| 24sp | 609,427 | 9,182 | **66x** |

Reading on costs **10,140** characters at 19sp — an extension is a third of an
opening, and it is prefetched three pages before the reader arrives, so it is never on
a page turn. A window is cheaper at large type because a page holds less of the book
and a window is a fixed number of pages; the whole chapter cost the same whatever the
reader had chosen, which was the shape of the problem.

**Where a chunk is cut, and why not at a paragraph.** At a page boundary the paginator
itself just produced — which is already a line boundary, because `Paginator` only ever
splits where the measurer reported a line end. Three things follow. A single enormous
block needs no special case, which matters because the one-block chapter (a TXT with
no blank lines, a PDF whose reflow merged everything) is exactly the shape that causes
this and has no paragraph boundary to cut at. The first page of a chunk starts
mid-paragraph and already draws as a continuation, because `Indentation.shouldIndent`
and `ChapterOpening.opensChapter` both refuse a block resumed part-way through. And no
page is ever short: the only page a chunk may end on is one `flush()` produced inside
the layout loop, which is a page that ran out of height.

**How I know a seam is invisible, rather than merely small.** The claim is an equality:

> Laying a chapter out as a sequence of chunks, each starting where the last one
> stopped, produces **exactly** the pages that laying the whole chapter out produces.

It holds because a fresh lay-out at a page boundary is in the state whole-chapter
pagination is in when it reaches one — `used` zero, the page empty, spacing above the
first block on a page zero by definition, indent and raised initial already
suppressed. The only thing reset is the learned characters-per-line, which decides how
much text one `measure` call asks for and never where a line breaks.
`ChunkedPaginationTest` chunks four chapter shapes at budgets of 1, 2, 3, 5, 8, 13 and
40 pages and compares the concatenated page list to `paginate(whole)` **slice for
slice**. Deliberately breaking the heading rule was checked to break it.

Two edges needed work to make that an equality. Orphan control moves a heading that
ended a page onto the next one, and a chunk that cannot see the next page cannot — so
**a chunk never stops on a page whose last block is a heading**; it runs on by a page,
which is where that heading was going. And the carry is read off the last page's *end*
rather than tracked alongside it, because orphan control adds slices to the front of
that page and a separately tracked cursor would then describe a page that no longer
exists.

**The one case that is not an equality, and three things the plan got wrong.**

Forward extension is a pure append and front-trimming is a pure drop, so neither can
be seen. Backward extension re-tiles and *cannot not*: pages tile the text, so the
pages before a place must end exactly at it, and pagination only runs forwards. The
only exact prepend available would leave a deliberately short page at the seam, which
is the most visible thing a reader could be shown.

*The plan said to show the page ending at or before where the reader was.* Measured on
the fixture, that page ends up to **680 characters short** — a single backward turn
skipped most of a page, text on neither the page they left nor the page they were
given. It shows the page holding the character immediately *before* their old start
instead: it begins before them, so the turn really moves back, and it runs to at least
where they were, so **nothing is skipped**. The cost is an overlap of at most a page —
the top of what they were reading appearing at the foot of what they are given, which
is text they were looking at a second ago. Once per eight pages of *backward* travel,
never on a forward turn, never on a type-size change, and never speculatively, because
moving a page under a reader who did not ask is the one thing a prefetch must not do.

*The window anchor had to be snapped to a grid, or the book walks backwards.* Caught
by `ResumeLoopTest`, not by reading. Where a window starts decides where its pages
break, and the Reader saves the top of the page it was on — so a start taken as
"exactly `charsBehind` before the reader" is a different start every session, landing
the saved place mid-page and saving a slightly earlier one. A book left at `(219, 480)`
came back at `(218, 640)`, and every open would have taken it back another fraction of
a page. `ReaderWindow.anchorOffset` snaps to a grid of half `charsBehind`, so every
place in a band gives the same start, the tiling is the same, and a saved page top is
still a page top. Three opens and closes, and the second and third are identical.

*Two effects could write two different page lists.* Growth and repagination are
separate effects — they have to be, because one is keyed on the reader's position and
the other must not be — so a reader who tapped A+ while near the window's edge had the
extension, laid out at the *new* size, appended to pages laid out at the *old* one.
Half one measurement and half another, in one page list: the renderer draws more lines
than were budgeted, and the reader's character offset resolves against breaks that do
not exist. That is the same class of fault as the four concurrent paginations of the
last round, arrived at from the other direction. `ReaderState.windowLayout` records
what the pages in hand were measured against, and `ReaderLayout.mayGrowWindow` is the
one rule both call sites ask. A turn made in that window is queued rather than
swallowed.

*And a page turned during a prefetch was undone by it.* Laying out twelve pages takes
tens of milliseconds, and a reader three pages from the edge is reading — so they turn
a page while it runs. Appending to the window captured *before* the lay-out started
wrote that window's page index back and put them silently on the page they had been on
when it began. Laying out and appending are two calls now, and the append takes the
window as it is at the moment it happens.

*A chapter that opens with a `PageBreak` did not start at `TextAnchor(0, 0)`.*
`Chapter.cursorAt` steps over blocks with no characters — correctly, since a cursor
inside one is ambiguous with the start of the next — so asking it for offset zero on a
reflowed PDF answers `TextAnchor(1, 0)`. Three things then went wrong at once and all
of them silently: the page break's own slice dropped out of the window, the chapter
header was not drawn because the window did not look like it began at the beginning,
and turning back out of the chapter re-anchored for ever instead of opening the
previous one. The chapter's beginning is `TextAnchor(0, 0)` and nothing else.

**The page count, and a progress bar that never moved.** `state.pages` is a window, so
`"38% · page 12 of 719"` could not survive — the denominator would have been the
window's. It reads `"38% · about page 190 of 314"` now, counted in printed pages from
the book's characters, which is the unit Book Details already states a length in and
the only one a reader can check against a spine. For this book it says **314 against
the PDF's real 315**. "about" is not decoration; and it has the compensation of no
longer changing every time the reader changes the type size.

Underneath it, a real bug. `ReaderState.progress` added `slice.startChar` — the offset
inside the reader's *own block* — to the chapter's start offset. On a book of many
small chapters the chapter offsets carried the number and it looked right; on a book
with no outline, which is one chapter of 8,621 blocks, it never exceeded the length of
one paragraph. **A reader three hundred screens into this novel was shown 0%**, and so
were the widget and Book Details. `Chapter.blockStarts` is a lazy prefix sum, and the
same array is what lets a window be anchored *n* characters behind the reader without
walking 8,621 blocks on every page turn.

**Nothing is stored, nothing is migrated, nothing is re-extracted.** A chunk is a
cursor into a `Chapter` that is already on disk, worked out in memory at lay-out time.
Every book already on the device works untouched — no Room migration, no schema
change, no `FIXTURE_VERSION` bump — and a test serializes a `Chapter` and fails if a
chunk boundary ever reaches the JSON. Chunking at import was considered and rejected
for that reason and one more: a stored boundary would have been chosen for one viewport
and one type size, and wrong for every other.

Contents still lists the book's own chapters or nothing. It never sees a chunk.

**What a device still has to answer.** Every number above is measured characters on a
JVM; none of it is a stopwatch, because there is no phone here.

- Whether a type-size change on the 315-page novel now *feels* immediate.
- Whether a forward seam can be seen. Read about twenty pages without stopping and
  watch for a page that is shorter than the others, a stutter on one particular turn,
  or a paragraph that breaks oddly — the prefetch fires three pages before the edge,
  so the tell would be a hitch on one turn in twelve.
- Whether a backward seam can be seen. Turn back eight or more pages in one go: at the
  seam the page will overlap the one before it by up to a page. Nothing should ever be
  *missing*.
- That the chapter header appears on the book's first page and on no other.
