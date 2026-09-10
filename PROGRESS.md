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

- [x] Plan 1 — `:core` pipeline **COMPLETE** (153 tests) · `docs/superpowers/plans/2026-09-11-folio-pipeline.md`
- [x] Plan 2 — persistence + import **COMPLETE** (216 JVM + 14 device tests)
- [x] Plan 3 — design system + Library/Details UI **COMPLETE** (287 JVM + 15 device)
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
