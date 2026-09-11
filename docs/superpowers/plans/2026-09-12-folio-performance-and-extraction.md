# Reader Performance and Extraction Quality — Implementation Plan

> **For agentic workers:** steps use checkbox (`- [ ]`) syntax. Gate on
> `./scripts/check.sh` before every commit. Never commit red.

**Goal:** make long books open and repaginate without hanging, and stop books
arriving with a filename for a title, no cover, and OCR junk in the text.

**Architecture:** two independent tracks. Track A is confined to `Paginator`,
the block-text representation, and a page cache in `ReaderHost`. Track B adds a
cover extractor and a metadata resolver to the import pipeline, and a junk
filter between OCR and reflow. Neither track changes the normalized `Book`
contract the Reader consumes.

**Tech Stack:** unchanged. No new dependencies — every version stays pinned.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`

## Global Constraints

- Kotlin 2.3.21, AGP 9.4.0, compileSdk 37 / targetSdk 36 / minSdk 26. Never bump.
- Apache PDFBox 2.0.37 stays test-only and must never reach the device.
- `:core` is pure Kotlin/JVM with JUnit 5. `:app` is Android with JUnit 4.
- **Conservatism rule.** Extraction must never delete content it is not
  confident about. Every test asserting removal must also assert the surviving
  body text.
- No network, ever. No Play-services-delivered models. Metadata comes from the
  file itself or not at all.
- Reading position is character-based and must survive every change here.

---

## Track A — Reader performance

### Task A1: Bound what the paginator measures

**Problem:** `Paginator` hands the measurer `text.substring(cursor)` — the whole
remaining block — to find one page of lines. Measured: a 400k-character single
block lays out 30.1M characters, 75× its own length, and the ratio doubles as
the book doubles. Normal paragraph blocks are 1.2× and fine; a single huge
block is the trigger (a TXT with no blank lines, a merged PDF reflow).

**Files:**
- Modify: `core/src/main/kotlin/app/folio/core/paginate/Paginator.kt`
- Test: `core/src/test/kotlin/app/folio/core/paginate/PaginationCostTest.kt` (create)

- [x] **Step 1: Write the failing cost test**

A counting measurer records characters laid out; the test asserts the ratio
stays near-linear for a single huge block.

```kotlin
@Test
fun `a huge block costs about what it should to paginate`() {
    val m = CountingMeasurer()
    val chapter = chapterOfOneBlock(400_000)
    Paginator(m).paginate(chapter, viewport, settings)
    val ratio = m.charsMeasured.toDouble() / 400_000
    assertTrue("laid out ${"%.1f".format(ratio)}x the chapter", ratio < 4.0)
}
```

- [x] **Step 2: Run it and watch it fail at ~75x**

`./gradlew :core:test --tests "*PaginationCostTest*"`

- [x] **Step 3: Measure a window instead of the remainder**

Estimate the characters that could fill the remaining height from the previous
measurement's character-per-line rate, take a window of twice that, and widen
only when the window filled the page without running out of text. Use
`subSequence` rather than `substring` where the measurer allows it.

- [x] **Step 4: Run the cost test and the whole paginator suite**

Conservation (every character appears exactly once, in order) is the invariant
that must not move. `./scripts/check.sh`

- [x] **Step 5: Commit**

### Task A2: Compute block text once, not per frame

**Problem:** `ContentBlock.plainText` is a computed extension property doing
`spans.joinToString("")`. `ReaderScreen` calls it per slice **per
recomposition**, so every page turn rebuilds the full text of each block on
screen — 400k characters per frame on a big block.

**Files:**
- Modify: `core/src/main/kotlin/app/folio/core/model/Chapter.kt`
- Modify: `core/src/main/kotlin/app/folio/core/paginate/Paginator.kt`
- Modify: `app/src/main/kotlin/app/folio/android/ui/reader/ReaderScreen.kt`
- Modify: `app/src/main/kotlin/app/folio/android/ui/reader/ReaderState.kt`
- Test: `core/src/test/kotlin/app/folio/core/model/ChapterTextTest.kt` (create)

- [x] **Step 1: Write the failing test** — `Chapter.blockTexts` is computed once
      and is identical to mapping `plainText` over the blocks.
- [x] **Step 2: Run it, watch it fail to compile**
- [x] **Step 3: Add a lazily-computed `blockTexts` to `Chapter`**, marked
      `@Transient` so serialization is unchanged, and read it everywhere the hot
      path currently calls `plainText`.
- [x] **Step 4: Gate** — `./scripts/check.sh`
- [x] **Step 5: Commit**

### Task A3: Keep paginated chapters

**Problem:** returning to a chapter repaginates it from scratch.

**Files:**
- Create: `app/src/main/kotlin/app/folio/android/ui/reader/PageCache.kt`
- Modify: `app/src/main/kotlin/app/folio/android/ui/reader/ReaderHost.kt`
- Test: `app/src/test/kotlin/app/folio/android/ui/reader/PageCacheTest.kt`

- [x] **Step 1: Failing test** — a cache keyed on chapter, viewport and
      typography returns the same pages for a repeat request and misses when
      any key component changes.
- [x] **Step 2: Run it, watch it fail**
- [x] **Step 3: Implement a 3-entry LRU** and consult it in `loadChapter`.
- [x] **Step 4: Gate**
- [x] **Step 5: Commit**

---

## Track B — Extraction quality

### Task B1: Covers

**Problem:** `coverPath` is null for every format, so every book is a gradient.

**Files:**
- Create: `core/src/main/kotlin/app/folio/core/epub/EpubCover.kt`
- Modify: `core/src/main/kotlin/app/folio/core/epub/EpubParser.kt`
- Modify: `app/src/main/kotlin/app/folio/android/importer/BookImporter.kt`
- Modify: `app/src/main/kotlin/app/folio/android/data/BookStore.kt`
- Test: `core/src/test/kotlin/app/folio/core/epub/EpubCoverTest.kt`

EPUB declares its cover two ways and needs both, plus fallbacks:
1. EPUB 3: manifest `<item properties="cover-image">`
2. EPUB 2: `<meta name="cover" content="{id}"/>` → manifest item with that id
3. Fallback: manifest item whose id or href contains "cover"
4. Fallback: the first image in the first spine document

PDF renders page 1 through the rasterizer we already have.

- [x] **Step 1: Failing tests** for all four EPUB resolution paths, including a
      book that declares no cover at all (must stay null, not invent one).
- [x] **Step 2: Run them, watch them fail**
- [x] **Step 3: Implement `EpubCover.resolve(container)`** returning the
      manifest href or null.
- [x] **Step 4: Write the image to book storage** and set `coverPath`.
- [x] **Step 5: PDF covers** — render page 1 at thumbnail scale on import.
- [x] **Step 6: Gate, then look at the library on device**
- [x] **Step 7: Commit**

### Task B2: Real titles and authors

**Problem:** `PdfPipeline` sets `title = <filename>` and `author = null` and
never reads the document's own metadata.

**Files:**
- Create: `core/src/main/kotlin/app/folio/core/metadata/TitleResolver.kt`
- Modify: `core/src/main/kotlin/app/folio/core/source/PdfTextSource.kt`
- Modify: `app/src/main/kotlin/app/folio/android/pdf/AndroidPdfTextSource.kt`
- Modify: `core/src/main/kotlin/app/folio/core/pdf/PdfPipeline.kt`
- Test: `core/src/test/kotlin/app/folio/core/metadata/TitleResolverTest.kt`

Resolution order, each step validated before it is trusted:
1. PDF DocInfo `Title` / `Author`, rejected when it looks like a filename
   (`Microsoft Word - …`, contains `.doc`/`.pdf`/`.tex`, equals the filename)
2. The largest type on page 1 — title pages set the title biggest, and
   `TextRun` already carries `fontSize` and `bold`
3. Filename patterns: `Author - Title`, `Title - Author`, `Title (Author)`
4. The filename, as today

- [ ] **Step 1: Failing tests** for each rule and each rejection, including
      "a garbage DocInfo title falls through to the page-1 heading".
- [ ] **Step 2: Run them, watch them fail**
- [ ] **Step 3: Implement the resolver** as a pure function in `:core`.
- [ ] **Step 4: Expose DocInfo through `PdfTextSource`** and wire it up.
- [ ] **Step 5: Gate**
- [ ] **Step 6: Commit**

### Task B3: Drop OCR junk without eating the book

**Problem:** every line ML Kit returns goes into the book. Confidence is only
averaged into a flag, never used to reject. Photographed pages contribute page
edges, fingers, shadows and facing-page bleed as "text".

**Files:**
- Create: `core/src/main/kotlin/app/folio/core/ocr/JunkFilter.kt`
- Modify: `core/src/main/kotlin/app/folio/core/source/OcrEngine.kt`
- Modify: `app/src/main/kotlin/app/folio/android/ocr/MlKitOcrEngine.kt`
- Modify: `core/src/main/kotlin/app/folio/core/pdf/PdfPipeline.kt`
- Modify: `core/src/testFixtures/kotlin/app/folio/core/fixtures/FixtureBuilder.kt`
- Test: `core/src/test/kotlin/app/folio/core/ocr/JunkFilterTest.kt`

Three independent signals, and a line is dropped only when it is both
low-confidence and badly shaped — never on one signal alone:
- Confidence below a floor, using per-element confidence from ML Kit rather
  than the line average we read today
- Token shape: non-alphabetic ratio, no vowels beyond length 4, a single
  character repeated, character entropy
- Geometry: the line's box sits outside the page's detected text column

**Conservatism:** every test asserting a junk line is removed must also assert
the body text around it survives intact.

- [ ] **Step 1: Add a junk fixture** — a scanned page carrying marginalia, a
      stray mark, and a low-confidence smear alongside real prose.
- [ ] **Step 2: Failing tests** for each signal, plus a test that prose with
      unusual words (proper nouns, foreign phrases) is *not* dropped.
- [ ] **Step 3: Run them, watch them fail**
- [ ] **Step 4: Implement `JunkFilter.clean(page, column)`**
- [ ] **Step 5: Carry per-element confidence through `OcrLine`**
- [ ] **Step 6: Gate**
- [ ] **Step 7: Commit**

### Task B4: Preprocess before recognising

**Problem:** pages go to OCR exactly as rendered — no grayscale, no contrast
normalisation, no binarization. Photographed pages suffer most.

**Files:**
- Modify: `app/src/main/kotlin/app/folio/android/pdf/AndroidPageRasterizer.kt`
- Create: `app/src/main/kotlin/app/folio/android/ocr/PagePreprocessor.kt`
- Test: `app/src/androidTest/kotlin/app/folio/android/PagePreprocessorTest.kt`

Grayscale plus adaptive contrast, and OCRmyPDF's conditional trick: recognise a
sample of pages both raw and preprocessed, keep whichever scores higher mean
confidence, then use that decision for the rest of the book.

- [ ] **Step 1: Failing instrumented test** — a low-contrast page recognises
      better preprocessed than raw.
- [ ] **Step 2: Run it, watch it fail**
- [ ] **Step 3: Implement grayscale + contrast stretch**
- [ ] **Step 4: Implement the sample-and-choose decision**
- [ ] **Step 5: Device gate** — `./scripts/check-device.sh`
- [ ] **Step 6: Commit**

---

## Order

A1 and A2 first: the user is feeling those now, and both are contained. Then
B1 and B2, which are the most visible per hour of work. Then B3, which needs
new fixtures before it can be honest. B4 last, and only if measurement says the
preprocessing earns its cost.

## Deliberately not doing

- **Online metadata lookup** (ISBN, OpenLibrary, Google Books). It is how
  Calibre gets good metadata and it is off the table by the project's own
  no-network constraint. This is a real quality ceiling, accepted knowingly.
- **ML Kit Document Scanner API**, despite doing exactly the perspective and
  shadow correction photographed books need: it is delivered via Google Play
  services as a dynamic download, which breaks the offline guarantee, and it is
  a capture-time flow rather than something that can run over an imported PDF.
  It is the right tool for a future "photograph a book" feature, not for this.
- **Splitting huge blocks at import.** The handoff forbids restructuring
  content, and A1 makes the performance argument for it moot.
