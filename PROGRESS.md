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
| **Screenshot** | `./scripts/shot.sh <name>` — writes `/tmp/folio-<name>.png` |
| **Logs** | `./scripts/logs.sh` (follow) or `--dump` (what already happened) |
| Screenshots | `docs/screenshots/` |
| Spec | `docs/superpowers/specs/2026-09-11-folio-android-design.md` |
| Plans | `docs/superpowers/plans/` |

**Working end to end:** import (EPUB, TXT, text PDF, scanned PDF via real on-device
ML Kit OCR) → reflow and chapter detection → Library → Book Details → Reader with
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
- [x] Reading reminders **COMPLETE** (656 JVM) · `docs/superpowers/plans/2026-09-13-folio-notifications.md`

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
   companion into `FolioConstants` with the reasoning attached, plus a test asserting
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

- **Task 6** `ImportProgressStore`, `ImportWorker`, `FolioWorkerFactory`,
  `ImportCoordinator`. Unique work per book id with `KEEP`, so a double tap cannot
  import the same file twice. Progress is written atomically — temp file then rename —
  because a process killed mid-write would otherwise leave a truncated state file that
  reads as corrupt on restart; there is a test that a truncated file reads as absent
  rather than throwing, and another that no `.tmp` survives a save.

  OCR page counts are persisted so a restarted import does not redo recognition it
  already finished. On a 400-page scan that is the difference between resuming and
  starting over.

Only two things left in Plan 2: `FolioApp` wiring (Task 7) and the instrumented
import tests (Task 8).

### 2026-09-11 04:35 — PLAN 2 COMPLETE. 216 JVM tests + 14 device tests, all green

Gates: `./scripts/check.sh` → 216, 0 failures. `./scripts/check-device.sh` → 14, 0 failures.

Tasks 7–8 done: `FolioApp` wires the graph and supplies WorkManager's factory, and
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

**A third bug came from Task 7 itself.** Registering `FolioApp` in the manifest made
Robolectric instantiate it, and `FolioGraph` built `MlKitOcrEngine()` eagerly —
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
  `FolioStrings`, `MainActivity` with the SAF picker, and `FolioRoot`.

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

Also deleted a second piece of my own scaffolding: `FolioNav` briefly had a phantom
`Box` and an identity `matchParentSizeSafe()` that existed only to make a dead
`Modifier` chain look used.

`FolioStrings` now centralises copy with two guards: no `FailureReason` name can
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
literals to the OKLCH tokens through the same transform `FolioColors` uses,
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

**Back** now means "out of this". One reducer, `ui/nav/FolioBack.kt`, describes every
level, and `FolioRoot` holds the only `BackHandler`. Only the Library asks before
closing. The reducer is pure, so `FolioBackTest` can walk it from the deepest state and
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

## 2026-09-13 — Reminders that stay quiet on the days it matters

Plan: `docs/superpowers/plans/2026-09-13-folio-notifications.md`, branch
`agent/notifications`. All eight tasks ticked. **656 JVM tests, 0 failures.**

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

**Not verified on a device.** Everything above is JVM and Robolectric; the emulator
was in use. What still needs a real phone: the notification's appearance and the
`ic_book` small icon at status-bar size, the system permission dialog, the deep link
to notification settings, and — the one that cannot be simulated — whether Doze
actually delivers inside the three-hour window on a phone left alone overnight.
