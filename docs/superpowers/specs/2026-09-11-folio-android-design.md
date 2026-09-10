# Folio — Android Reading App: Design

Date: 2026-09-11
Status: Approved for planning

## 1. Purpose and scope

Folio is a minimalist reading app. A user imports an EPUB, PDF, or TXT file; Folio turns
it into a clean, readable book and tracks the reading habit that follows. The design is
fixed: `design_handoff_folio_reading_app/README.md` and its prototype are the visual
specification, and this document does not revisit them.

What this document covers is the application behind that design, with the weight on the
import → extraction → normalization → reading pipeline.

### Constraints fixed by the user

- **Android-first.** The handoff describes an iOS-first design. The visual language is
  preserved exactly; the interaction primitives are Android-native (Storage Access
  Framework picker, Material3 `ModalBottomSheet`, system back).
- **Nothing leaves the device.** No backend, no account, no network calls, no telemetry,
  no crash reporting. OCR runs on-device against a bundled model.
- **Books are copied into app-private storage on import.** The original stays where the
  user put it and is never modified. Folio's copy lives in `filesDir` and is removed on
  uninstall.
- **Kotlin throughout**, Jetpack Compose for UI.

### Non-goals

Cloud sync, accounts, a store or catalogue, social features, DRM-protected books,
text-to-speech, and translation are all out of scope. Nothing in the architecture should
foreclose sync later, but nothing should be built for it now.

## 2. Environment

Verified present on the development machine as of this date:

| Component | Version |
|---|---|
| JDK (build) | Temurin/Homebrew OpenJDK 21.0.12.1 |
| Android SDK platform | android-36 |
| Build tools | 36.1.0 |
| Platform tools (adb) | 37.0.1 |
| Emulator | 37.1.11, arm64 `google_apis` system image |

`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.

Homebrew's default `openjdk` is 26, which the Android Gradle Plugin does not support.
Gradle must be pinned to JDK 21.

Target configuration: `minSdk 26`, `compileSdk 36`, `targetSdk 36`.

No physical device is available. Emulator-only testing means OCR throughput and
large-book pagination timings are not representative; those numbers will be reported as
emulator figures, never as device performance claims.

## 3. Module structure

Two Gradle modules, divided on one rule: **`:core` does not depend on the Android SDK.**

```
:core   kotlin("jvm")               — no android.* imports
        model/       normalized book representation
        source/      interfaces: PdfTextSource, PageRasterizer, OcrEngine
        epub/        EPUB container + OPF + XHTML parsing
        txt/         plain text parsing
        pdf/         PDF pipeline orchestration
        reflow/      line assembly, columns, headers, paragraphs, hyphenation
        structure/   chapter and section detection
        normalize/   final NormalizedBook assembly
        habit/       streak, XP, level, milestone computation (pure functions)

:app    com.android.application     — depends on :core
        ui/          Compose screens, theme system, navigation
        data/        Room entities, DAOs, repositories, file storage
        work/        ImportWorker (WorkManager)
        pdf/         PdfBoxTextSource, AndroidPageRasterizer (PdfRenderer)
        ocr/         MlKitOcrEngine
```

Both modules are Kotlin. The split concerns dependencies, not language.

### Why this split

`:core` declares `PdfTextSource`, which returns positioned text runs for a page. `:app`
implements it with PdfBox-Android. **`:core`'s own tests implement it with Apache PDFBox
on the JVM.** Both produce the same DTO, so reflow, column detection, header stripping
and chapter inference — the logic most likely to be subtly wrong — are tested against
real PDF files under plain JUnit, in seconds, with no emulator.

Only ML Kit, Room, WorkManager and Compose require a device.

Finer-grained modules (`:parser-epub`, `:reflow`, …) were considered and rejected as
premature. Package boundaries inside `:core` give the same separation without the Gradle
overhead.

## 4. The normalized book

The reader consumes only this. It has no knowledge of EPUB, PDF, OCR, or TXT.

```kotlin
data class Book(
    val id: BookId,
    val title: String,
    val author: String?,
    val coverPath: String?,          // relative to the book's directory
    val metadata: BookMetadata,      // language, publisher, identifiers, subjects
    val sourceFormat: SourceFormat,  // EPUB | PDF_TEXT | PDF_OCR | TXT
    val status: ProcessingStatus,
    val chapters: List<ChapterRef>,  // content loaded lazily, per chapter
)

data class Chapter(
    val index: Int,
    val title: String?,
    val blocks: List<ContentBlock>,
    val startCharOffset: Int,        // cumulative across the book
    val charCount: Int,
)

sealed interface ContentBlock {
    data class Paragraph(val spans: List<InlineSpan>) : ContentBlock
    data class Heading(val level: Int, val spans: List<InlineSpan>) : ContentBlock
    data class ListItem(val ordinal: Int?, val spans: List<InlineSpan>) : ContentBlock
    data class BlockQuote(val spans: List<InlineSpan>) : ContentBlock
    data class Image(val path: String, val caption: String?) : ContentBlock
    data class PageBreak(val sourcePage: Int) : ContentBlock
}

data class InlineSpan(val text: String, val style: Set<InlineStyle>)  // EMPHASIS, STRONG, CODE
```

### Position is character-based, never page-based

Reflowed text repaginates whenever font family, size, or alignment changes, so a page
number is not a stable identity for a location in a book.

```kotlin
data class ReadingPosition(val chapterIndex: Int, val blockIndex: Int, val charOffset: Int)
```

Progress is cumulative characters read over total characters. Pages are a *derived view*
produced by the paginator and are never persisted as truth. This is what makes "reopen
exactly where you stopped" survive a typography change, which a page-number model cannot.

### Storage layout

```
filesDir/books/<bookId>/
    original.<ext>          the imported copy, never modified
    cover.jpg               extracted or generated
    chapters/000.json       one file per chapter
    chapters/001.json
    images/<hash>.jpg
    processing.json         stage + partial artifacts, for resume
```

One file per chapter, deliberately — not a single document blob. The reader loads
chapters lazily, and an interrupted import does not lose completed work.

## 5. Processing pipeline

```
ImportRequest(uri)
   ↓ copy into filesDir, compute hash, detect format by magic bytes (not extension)
   ↓
   ├── EPUB → EpubParser
   ├── TXT  → TxtParser
   └── PDF  → PdfPipeline
                ↓ extract text via PdfTextSource
                ↓ classify: text-based or scanned
                ↓ (scanned pages only) rasterize → OcrEngine
                ↓
   ↓
Reflow  →  StructureDetector  →  Normalizer  →  NormalizedBook  →  persist
```

### States

`idle → importing → detectingFormat → extracting → ocr → detectingStructure →
normalizing → ready`, with `failed` reachable from any stage. Stage and partial artifacts
are written to `processing.json` after each page or chapter completes, so an interrupted
OCR resumes at the last finished page rather than restarting.

Runs in a `CoroutineWorker` under WorkManager, unique work per book id, promoted to a
foreground service for long jobs. CPU work on `Dispatchers.Default`. The UI observes
state from Room and never blocks.

### EPUB

`java.util.zip` for the container, jsoup for XHTML. Read `META-INF/container.xml` → OPF
→ manifest, spine, metadata, and the cover reference. Each spine document becomes a
chapter; the nav document (EPUB 3) or NCX (EPUB 2) supplies titles.

Markup is converted, not rendered: `<p>` → Paragraph, `<h1>`–`<h6>` → Heading, `<li>` →
ListItem, `<blockquote>` → BlockQuote, `<img>` → Image with the resource extracted to
`images/`, `<em>`/`<i>` → EMPHASIS, `<strong>`/`<b>` → STRONG. Script, style, and
navigation furniture are dropped. Unknown block elements degrade to Paragraph rather
than being discarded.

### PDF text extraction

`PdfTextSource` yields, per page, a list of runs:

```kotlin
data class TextRun(
    val text: String,
    val x: Float, val y: Float, val width: Float, val height: Float,
    val fontSize: Float, val fontName: String, val bold: Boolean, val italic: Boolean,
)
```

PdfBox-Android (Apache 2.0) supplies this via a `PDFTextStripper` subclass harvesting
`TextPosition`. Apache PDFBox exposes the same API on the JVM, which is what makes the
test double faithful rather than approximate.

### Scanned detection

Sampled, not exhaustive: first 5 pages plus a random 10% of the remainder. A book is
treated as scanned when the median extractable characters per page falls below
`SCANNED_CHARS_PER_PAGE = 100`,
corroborated by low text-area-to-page-area ratio and the presence of a full-bleed image
resource. Mixed documents OCR **only the pages that lack text** — the classification is
per page, and the threshold is a per-book median used to decide whether to bother
checking at all.

### OCR

ML Kit Text Recognition v2, bundled model (~4MB, Latin script), fully on-device with no
Play Services dependency and no network. Pages are rasterized with Android's
`PdfRenderer` at ~300 DPI equivalent, one page at a time to bound memory, recycled
immediately.

ML Kit returns blocks, lines and elements with bounding boxes and confidence. These map
onto the same `TextRun` shape as extracted text, so **everything downstream of extraction
is identical for OCR and text PDFs** — one reflow implementation, one structure detector.

Failure handling: if OCR throws, or mean element confidence across the book falls below
`MIN_OCR_CONFIDENCE = 0.55`, the book is still imported, flagged, and offered as "Read
original PDF".

## 6. Reflow

Applied in order, each step confidence-scored:

1. **Line assembly.** Cluster runs into lines by y-band, tolerance derived from median
   glyph height on the page.
2. **Column detection.** x-position histogram per page; a clear gutter splits the page.
   Applied only when consistent across ≥60% of pages, so a single odd page cannot
   corrupt a whole book.
3. **Header/footer removal.** Normalize the top-most and bottom-most line of each page
   (strip digits, collapse whitespace); drop what recurs on ≥50% of pages at a similar y.
   Separately drop lines near a page edge that are mostly digits or roman numerals.
4. **Paragraph assembly.** Break when the previous line ends short of the right margin,
   when indentation shifts, or when the vertical gap exceeds ~1.5× median leading.
5. **De-hyphenation.** A line ending in `-` followed by a line starting lowercase joins,
   dropping the hyphen. Words that are plausibly genuinely hyphenated keep it.

### The conservatism rule

Where confidence is low, **emit the text as extracted rather than as "cleaned"**. Losing
content is a worse failure than leaving an artifact in place, because the user can read
past an artifact but cannot recover a deleted paragraph.

If overall reflow confidence falls below `MIN_REFLOW_CONFIDENCE = 0.5`, the book still
imports and appears in
the Library, marked `reflowFailed`, and Book Details offers **Read original PDF** — a
`PdfRenderer`-backed page viewer. The book is never rejected for reflow failure alone.

## 7. Chapter detection

A separate step, run over normalized blocks, in strict order of authority:

1. **PDF outline / bookmarks**, when present — authoritative.
2. **EPUB spine plus nav/NCX** — authoritative.
3. **Heuristics**, scored and combined: font size relative to body median, bold, all-caps,
   centering, numbering patterns (`Chapter N`, `N.`, roman numerals, `Part N`, bare
   numerals on an otherwise empty line), a large preceding vertical gap, and position at
   the top of a page.

Numbered-chapter patterns are one signal among several, not an assumption. Books using
titled-only chapters, part/section hierarchies, or roman numerals are handled by the same
scorer.

**If no signal scores confidently, the book becomes a single chapter containing
everything.** Invented structure is worse than absent structure: a wrong chapter boundary
corrupts navigation and progress permanently, while a single chapter is merely plain.

## 8. Reading engine

### Pagination

Measured pagination, chosen over a scrolling list. The design shows discrete pages with
tap-to-toggle chrome, and Book Details carries a "pages left" statistic; neither is
meaningful without real pages.

The paginator lays out the current chapter with Compose's `TextMeasurer` in a background
coroutine, keyed by `(viewportSize, fontFamily, fontSize, alignment, lineHeight)`. Only
the current chapter is paginated, with neighbours prefetched, which bounds both memory
and latency on large books. Results are cached per chapter and invalidated when the key
changes.

On a typography change: repaginate the current chapter first, restore position by
`charOffset`, then prefetch outward. The reader never blocks on a full-book layout.

### Reading state

Current chapter, position, progress percentage, estimated time remaining (from a rolling
words-per-minute estimate, not a fixed constant), resume position, bookmarks, highlights,
typography preferences, theme, and table of contents. Position is persisted on every page
turn and on lifecycle pause, debounced.

## 9. Persistence

Room, with these entities: `Book`, `ReadingProgress`, `ReadingSession`, `Bookmark`,
`Highlight`, `ReadingGoal`, `ReadingDay`, `UserLevel`, `Milestone`, `AppSettings`.

Room holds metadata, progress and habit data. Chapter content, images and the original
file live on disk as described in §4. Bookmarks and highlights anchor to
`ReadingPosition` plus a text snapshot, so they survive repagination and can be repaired
if content is ever reprocessed.

## 10. Habit tracking

A `ReadingSession` is recorded from reader foreground time, paused by an idle timeout (no
page turn within `IDLE_TIMEOUT = 2 minutes`) and by app backgrounding, so the timer reflects reading
rather than an open screen. Sessions roll up into `ReadingDay`.

Streak is consecutive `ReadingDay`s meeting the goal. XP accrues from minutes read and
book completions; level and milestones derive from XP. All of this is computed by pure
functions in `:core/habit/`, making streak-boundary behaviour (timezone changes, midnight,
a missed day, goal changes mid-streak) directly unit-testable rather than something to be
verified by waiting a week.

Goal options are 5/10/20/30 minutes, per the design. No gamification chrome appears over
reading content.

## 11. Theme system

Four themes — light, pale, dark, e-ink — with tokens taken verbatim from the handoff.

The handoff specifies OKLCH; Compose has no OKLCH literal. Values are converted through a
proper OKLab → linear sRGB → sRGB transform into a generated Kotlin color table. The
conversion is verified by spot-checking rendered swatches against a browser rendering of
the prototype, because an unverified conversion drifts the entire palette subtly and
everywhere.

E-ink is, as the handoff directs, the light palette with a grayscale filter over the
content root — a `ColorMatrix` `RenderEffect` — not a hand-tuned fifth palette.

Navigation ships the **Pill** variant only. The other three explorations are not built.

Glass bottom sheets use `Modifier.blur`, which requires API 31+. Below that, a
translucent scrim of equivalent tone. This is the reason `minSdk` is 26 rather than lower.

## 12. Error handling

Every failure resolves to the designed error state — "We couldn't open this file." — with
"Try another file", and where a partial result exists, "Read original PDF".

Handled: corrupt EPUB, invalid PDF, encrypted or password-protected PDF, empty document,
OCR failure, unsupported file type, extraction failure, insufficient storage, and
interrupted processing. Format is detected by magic bytes rather than file extension, so
a mislabelled file fails cleanly instead of part-way through parsing.

Free space is checked against the source file size before copying. A partially imported
book is never left in the Library; its directory is removed on failure.

## 13. Testing

`:core` tests run under plain JUnit with no emulator and cover: normal EPUB, badly
formatted EPUB, text PDF, multi-column PDF, PDF with running headers and footers, PDF
with images, PDF with unusual chapter formatting, TXT, a large book, a corrupted file,
and an unsupported file. Scanned-PDF fixtures test the classifier; the OCR engine itself
is faked at the `OcrEngine` interface so reflow of OCR output is testable without a
device.

Reflow assertions are written against expected output text, so a regression that eats a
paragraph or mangles a heading fails a test rather than being noticed later in an
emulator.

Instrumented tests on the emulator cover the real ML Kit path, Room migrations, and the
full loop the brief specifies: import → process → library → open → read → close → reopen
→ resume exact position, for each format.

A pagination benchmark asserts the UI thread is not blocked while a large book is
processed.

## 13a. Tunable constants

These are heuristic starting values, defined in one place in `:core` rather than scattered
as literals, and expected to move as the test corpus grows. Each is covered by a fixture
that would fail if the value drifted far enough to change behaviour on a known book.

| Constant | Value | Governs |
|---|---|---|
| `SCANNED_CHARS_PER_PAGE` | 100 | median chars/page below which a PDF is treated as scanned |
| `MIN_OCR_CONFIDENCE` | 0.55 | mean OCR confidence below which the book is flagged |
| `MIN_REFLOW_CONFIDENCE` | 0.50 | reflow confidence below which "Read original PDF" is offered |
| `COLUMN_CONSISTENCY` | 0.60 | fraction of pages that must agree before a column split applies |
| `HEADER_RECURRENCE` | 0.50 | fraction of pages a line must recur on to be a running header |
| `PARAGRAPH_GAP_FACTOR` | 1.5 | multiple of median leading that forces a paragraph break |
| `IDLE_TIMEOUT` | 2 min | no page turn before a reading session pauses |
| `OCR_RENDER_DPI` | 300 | rasterization density for OCR |

## 14. Risks

1. **OKLCH → sRGB conversion.** An incorrect transform shifts every colour in the app
   slightly, and it would be easy to miss. Mitigated by generating the table and
   spot-checking against a browser render of the prototype.
2. **No physical device.** Emulator OCR and pagination timings are not device
   performance. These will be reported as emulator figures only.
3. **PdfBox-Android memory on large PDFs.** Page-at-a-time processing with explicit
   bitmap recycling; a large-book fixture guards this.
4. **ML Kit bundled model is Latin-script.** Other scripts need additional artifacts
   (~4MB each). Latin only for now; the `OcrEngine` interface leaves room.
5. **Measured pagination cost.** Mitigated by paginating only the current chapter and
   prefetching neighbours; the risk is latency on a typography change, bounded by chapter
   size rather than book size.
6. **Reflow is heuristic by nature.** It will be imperfect on adversarial PDFs. The
   conservatism rule and the "Read original PDF" fallback make imperfection recoverable
   rather than destructive.

## 15. Build order

1. Project scaffold, Gradle, module structure, CI-less local test wiring
2. Normalized model + storage layout + Room schema
3. TXT parser (simplest end-to-end path through the pipeline)
4. EPUB parser
5. PDF text extraction + the Apache PDFBox test double
6. Reflow
7. Chapter detection
8. Scanned detection + ML Kit OCR
9. Import flow: SAF picker, WorkManager job, processing states
10. Theme system + design tokens
11. Library, Book Details, empty and error states
12. Reader: pagination, typography, TOC, themes
13. Reading position persistence and resume
14. Bookmarks and highlights
15. Sessions, goals, streaks, XP, milestones, levels
16. Error handling and interrupted-processing recovery
17. Animation, transitions, performance, UI fidelity pass

Steps 2–8 need only the JDK. The emulator first becomes necessary at step 9.
