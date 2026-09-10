# Folio Persistence & Import (`:app`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist books locally and import them end to end — file picker → copy into app storage → background processing → Library — with real progress states and interrupted-import recovery.

**Architecture:** `:app` supplies the Android implementations of the interfaces `:core` declares (`PdfTextSource` via PdfBox-Android, `OcrEngine` via ML Kit, `PageRasterizer` via `PdfRenderer`), plus Room for metadata and a file-per-chapter store on disk. A `CoroutineWorker` under WorkManager runs the pipeline off the main thread and survives process death.

**Tech Stack:** Kotlin 2.3.21 via AGP 9.4.0 built-in Kotlin, Room 2.8.5 + KSP 2.3.12, WorkManager 2.11.2, PdfBox-Android 2.0.27.0, ML Kit text-recognition 16.0.1, Robolectric 4.16.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`

## Global Constraints

Every task's requirements implicitly include this section.

- **All Plan 1 constraints still apply.** Kotlin stays 2.3.21; `:core` gains no Android
  dependency; the conservatism rule holds; never commit red.
- **Do NOT add the `kotlin-android` plugin.** AGP 9.0+ has built-in Kotlin support and
  applying `org.jetbrains.kotlin.android` is a hard build failure. Verified 2026-09-11.
- **`compileSdk = 37` (`compileSdkMinor = 2`), `targetSdk = 36`, `minSdk = 26`.**
  Compose BOM 2026.09.00 requires compiling against 37. targetSdk stays at 36 because
  Robolectric 4.16 emulates no higher, and `Package targetSdkVersion=37 >
  maxSdkVersion=36` fails every JVM-side Android test. Do not "fix" this by raising
  targetSdk. Verified 2026-09-11.
- **`:app` uses JUnit 4, `:core` uses JUnit 5.** Robolectric supports JUnit 4 only, so
  `:app` must NOT call `useJUnitPlatform()`. Two modules, two frameworks, on purpose.
- **No network, no account, no telemetry.** The app makes no outbound calls of any kind.
  ML Kit must use the bundled model, never the Play-Services-delivered one.
- **The original file is copied, never modified**, into `filesDir/books/<id>/`, and the
  whole directory is removed if the import fails. A partially imported book must never
  appear in the Library.
- **Nothing heavy on the main thread.** Parsing, OCR and file copying run in the worker.

### Verified environment

| Component | Value | How it was verified |
|---|---|---|
| AGP | 9.4.0 | `:app:compileDebugKotlin` succeeds |
| Kotlin | 2.3.21, built into AGP | compiled |
| KSP | 2.3.12 | `:app:kspDebugKotlin` ran and generated Room code |
| Room | 2.8.5 | 3 DAO tests pass under Robolectric |
| Platform | `android-37.2` installed | `sdkmanager` |
| Emulator | AVD `folio_test`, API 36, boots headless in ~40s | booted, adb attached |
| Gate | `./scripts/check.sh` runs both modules | 156 tests passing |

---

## File Structure

```
app/src/main/kotlin/app/folio/android/
  FolioApp.kt                     Application, database + repository wiring
  data/Entities.kt                Room entities                      [done]
  data/FolioDatabase.kt           database, BookDao, ProgressDao      [done]
  data/Converters.kt              enum <-> string
  data/BookStore.kt               on-disk chapter/cover/original files
  data/BookRepository.kt          the single door between UI and data
  pdf/PdfBoxTextSource.kt         PdfTextSource via PdfBox-Android
  pdf/AndroidPageRasterizer.kt    PageRasterizer via PdfRenderer
  ocr/MlKitOcrEngine.kt           OcrEngine via ML Kit, bundled model
  work/ImportWorker.kt            CoroutineWorker running the pipeline
  work/ImportCoordinator.kt       enqueue, observe, cancel
  import/BookImporter.kt          copy, detect format, route to a parser
```

---

### [done] Task 1: Storage layout and `BookStore`

**Files:**
- Create: `app/src/main/kotlin/app/folio/android/data/BookStore.kt`
- Test: `app/src/test/kotlin/app/folio/android/data/BookStoreTest.kt`

**Interfaces:**
- Consumes: `Book`, `Chapter` from `:core`
- Produces:
  - `class BookStore(private val root: File)`
  - `fun bookDir(id: String): File`
  - `fun writeOriginal(id: String, source: InputStream, extension: String): File`
  - `fun writeChapters(id: String, chapters: List<Chapter>)` — one JSON file each
  - `fun readChapter(id: String, index: Int): Chapter?`
  - `fun writeCover(id: String, bytes: ByteArray): String`
  - `fun delete(id: String)`
  - `fun freeBytes(): Long`

- [ ] **Step 1: Write the failing test.** Cover: an original round-trips byte for byte;
  chapters are written one file per chapter and read back individually; reading a
  missing chapter returns null rather than throwing; `delete` removes the whole
  directory; writing a chapter does not load the others.

- [ ] **Step 2: Run it, confirm it fails.** `./gradlew :app:testDebugUnitTest --tests '*BookStoreTest*'`

- [ ] **Step 3: Implement.** Serialize `Chapter` with `kotlinx.serialization` to
  `books/<id>/chapters/%03d.json`. `writeOriginal` streams rather than buffering the
  whole file, since books can be hundreds of megabytes.

- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit** — `git commit -am "feat: on-disk book store, one file per chapter"`

---

### [done] Task 2: `BookRepository`

**Files:**
- Create: `app/src/main/kotlin/app/folio/android/data/BookRepository.kt`
- Create: `app/src/main/kotlin/app/folio/android/data/Converters.kt`
- Test: `app/src/test/kotlin/app/folio/android/data/BookRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `class BookRepository(db: FolioDatabase, store: BookStore)`
  - `fun observeLibrary(): Flow<List<LibraryBook>>`
  - `data class LibraryBook(id, title, author, coverPath, progress, lastOpenedAt, reflowFailed)`
  - `suspend fun save(book: Book)` — writes the row and the chapter files together
  - `suspend fun loadChapter(bookId: String, index: Int): Chapter?`
  - `suspend fun saveProgress(bookId: String, position: ReadingPosition, progress: Double)`
  - `suspend fun progressOf(bookId: String): ReadingPosition`
  - `suspend fun delete(bookId: String)` — row and files together

- [ ] **Step 1: Write the failing test.** Cover: saving a book makes it appear in
  `observeLibrary`; progress defaults to `ReadingPosition.START` for an unread book;
  saved progress round-trips exactly; `delete` removes both the row and the directory;
  the library orders by last opened, then added.

- [ ] **Step 2: Run it, confirm it fails.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit.**

---

### [done] Task 3: `PdfBoxTextSource` for Android

**Files:**
- Create: `app/src/main/kotlin/app/folio/android/pdf/PdfBoxTextSource.kt`
- Test: `app/src/test/kotlin/app/folio/android/pdf/PdfBoxTextSourceTest.kt`

**Interfaces:**
- Produces: `class AndroidPdfTextSource(file: File) : PdfTextSource`

The Apache-PDFBox version in `:core`'s tests is the reference implementation; this is
the same logic against `com.tom_roush.pdfbox`. Package names differ, the API does not.

- [ ] **Step 1: Write the failing test** — same assertions as `:core`'s
  `PdfBoxTextSourceTest`, run under Robolectric. PdfBox-Android needs
  `PDFBoxResourceLoader.init(context)` before use; do it in the test setup and in
  `FolioApp`.
- [ ] **Step 2–5:** as before.

---

### [done] Task 4: `AndroidPageRasterizer` and `MlKitOcrEngine`

**Files:**
- Create: `app/src/main/kotlin/app/folio/android/pdf/AndroidPageRasterizer.kt`
- Create: `app/src/main/kotlin/app/folio/android/ocr/MlKitOcrEngine.kt`
- Test: `app/src/test/kotlin/app/folio/android/pdf/AndroidPageRasterizerTest.kt`

`PdfRenderer` requires a `ParcelFileDescriptor`; render one page at a time and recycle
the bitmap immediately, or a large scan exhausts memory.

ML Kit's `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` uses the
**bundled** model. Do not use the Play-Services variant: it downloads at runtime, which
breaks the offline guarantee.

Convert ML Kit's top-left origin to the bottom-left origin `TextRun` uses:
`y = imageHeight - boundingBox.bottom`.

- [ ] Steps as before. The rasterizer is Robolectric-testable; the ML Kit engine needs
  the emulator, so its test goes in `androidTest` (Task 8).

---

### [done] Task 5: `BookImporter`

**Files:**
- Create: `app/src/main/kotlin/app/folio/android/import/BookImporter.kt`
- Test: `app/src/test/kotlin/app/folio/android/import/BookImporterTest.kt`

**Interfaces:**
- Produces: `class BookImporter(context, store, repository)` with
  `suspend fun import(uri: Uri, onProgress: (ProcessingStatus) -> Unit): Result<Book>`

Order: check free space against the source size → copy into `books/<id>/` → detect
format with `FormatDetector` → route to `EpubParser`, `TxtParser`, or `PdfPipeline` →
save. On any failure, delete the directory and return the `FailureReason`.

- [ ] **Step 1: Write the failing test.** Cover each fixture format end to end, plus:
  insufficient storage, unsupported file, corrupt file, and that a failed import
  leaves **no** directory and **no** row behind.
- [ ] **Step 2–5:** as before.

---

### [done] Task 6: `ImportWorker` and interrupted-import recovery

**Files:**
- Create: `app/src/main/kotlin/app/folio/android/work/ImportWorker.kt`
- Create: `app/src/main/kotlin/app/folio/android/work/ImportCoordinator.kt`
- Test: `app/src/test/kotlin/app/folio/android/work/ImportWorkerTest.kt`

Unique work per book id so a double-tap cannot import twice. Progress is published
through `setProgress` and mirrored into `processing.json` so an import interrupted by
process death resumes at its last completed stage rather than restarting.

- [ ] **Step 1: Write the failing test** using `TestListenableWorkerBuilder`. Cover:
  a successful import reaches `Ready`; a failure reaches `Failed` with the reason; a
  worker restarted mid-import resumes rather than redoing completed work.
- [ ] **Step 2–5:** as before.

---

### Task 7: `FolioApp` wiring

**Files:**
- Create: `app/src/main/kotlin/app/folio/android/FolioApp.kt`
- Modify: `app/src/main/AndroidManifest.xml`

Build the database, store, and repository once. Call `PDFBoxResourceLoader.init`.
No dependency-injection framework: the graph is small enough that a hand-written
holder is clearer than a library.

---

### Task 8: Instrumented tests on the emulator

**Files:**
- Create: `app/src/androidTest/kotlin/app/folio/android/OcrInstrumentedTest.kt`
- Create: `app/src/androidTest/kotlin/app/folio/android/ImportInstrumentedTest.kt`

The first real ML Kit run. Boot with `./scripts/emulator.sh`, then
`./gradlew :app:connectedDebugAndroidTest`.

- [ ] Render a page of `scanned.pdf` and assert ML Kit recovers recognisable words
  from it — the first end-to-end proof that the OCR path works on a real device image
  rather than against a fake.
- [ ] Import each fixture format and assert it reaches the Library.
- [ ] Assert a large book imports without an ANR.

---

## Self-Review

**Spec coverage.** §4 storage layout → Tasks 1–2. §5 pipeline wiring → Tasks 3–6.
§9 persistence → Tasks 1–2. §12 error handling → Task 5. §14 processing architecture
and interrupted recovery → Task 6.

Deferred to Plans 3–5: the theme system, all UI, pagination, habits.

**Type consistency.** `LibraryBook` is defined in Task 2 and consumed by Plan 3.
`BookStore`'s methods are fixed in Task 1 and used unchanged in Tasks 2, 5 and 6.
`AndroidPdfTextSource`, `AndroidPageRasterizer` and `MlKitOcrEngine` implement the
`:core` interfaces unchanged — no new shapes are introduced.

**Known gap.** ML Kit cannot be unit-tested on the JVM, so its correctness rests
entirely on Task 8's instrumented test. That test is therefore not optional.
