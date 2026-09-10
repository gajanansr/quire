# Folio Pipeline (`:core`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `:core` — a pure-Kotlin/JVM library that turns an EPUB, PDF, or TXT file into a normalized `Book`, fully tested against a generated corpus of real files, with no Android dependency.

**Architecture:** `:core` owns the normalized model and every parsing, reflow, and structure-detection decision. It declares interfaces (`PdfTextSource`, `PageRasterizer`, `OcrEngine`) that `:app` will implement with Android libraries later. Because `:core` never imports `android.*`, its tests run under plain JUnit in seconds — which is what makes the reflow and chapter-detection logic actually testable rather than nominally testable.

**Tech Stack:** Kotlin 2.3.21 (JVM), Gradle 9.7.1, JDK 21, jsoup, kotlinx-serialization, JUnit 5. Apache PDFBox 2.0.37 is **test-only**.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`

## Global Constraints

Every task's requirements implicitly include this section.

- **Kotlin is pinned to 2.3.21. Do not upgrade it.** Kotlin 2.4.20 exists and is tempting. KSP's latest release is 2.3.12, which does not support Kotlin 2.4.x, and Plan 2 needs KSP for Room. Upgrading Kotlin breaks the next plan.
- **Apache PDFBox is pinned to 2.0.37 and is `testImplementation` only.** It must never appear in `:core`'s main source set. The version matters: PdfBox-Android's latest is 2.0.27.0, a port of PDFBox **2.x**. Using PDFBox 3.x in tests would test against APIs the Android implementation does not have.
- **`:core` must not depend on the Android SDK.** No `android.*` import, no AGP plugin, no Android artifacts. If a task seems to need one, the design is wrong — stop and report rather than adding the dependency.
- **JDK 21 toolchain.** `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`. Homebrew's default `openjdk` is 26; AGP does not support it.
- **No network at runtime.** `:core` makes no network calls. Test fixtures are generated locally, never downloaded.
- **Conservatism rule (spec §6).** Where a heuristic's confidence is low, emit text as extracted rather than as "cleaned". A test that asserts content was removed must also assert the confidence that justified removing it. Losing a paragraph is a worse failure than leaving an artifact.
- **Tunable constants live in `FolioConstants` only.** Never inline a heuristic threshold as a literal at a call site.
- **Every task ends green.** `./gradlew :core:test` must pass before commit. Never commit red.

### Pinned versions

| Dependency | Version | Scope |
|---|---|---|
| Gradle | 9.7.1 | wrapper |
| Kotlin JVM | 2.3.21 | plugin |
| JDK toolchain | 21 | build |
| jsoup | 1.23.2 | `implementation` |
| kotlinx-serialization-json | 1.11.0 | `implementation` |
| kotlinx-coroutines-core | 1.11.0 | `implementation` |
| JUnit Jupiter | 5.14.4 | `testImplementation` |
| Apache PDFBox | 2.0.37 | `testImplementation` **only** |

### Deferred to Plan 2 (do not add now)

AGP 9.4.0, Room 2.8.5, KSP 2.3.12, Compose BOM 2026.09.00, WorkManager 2.11.2,
ML Kit text-recognition 16.0.1, PdfBox-Android 2.0.27.0.

---

## File Structure

```
folio/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml            version catalog — single source of versions
  gradle/wrapper/                      Gradle 9.7.1
  core/
    build.gradle.kts
    src/main/kotlin/app/folio/core/
      FolioConstants.kt                every tunable threshold (spec §13a)
      model/Book.kt                    Book, BookMetadata, SourceFormat, ProcessingStatus
      model/Chapter.kt                 Chapter, ChapterRef
      model/ContentBlock.kt            sealed ContentBlock, InlineSpan, InlineStyle
      model/ReadingPosition.kt         ReadingPosition, progress math
      source/PdfTextSource.kt          interface + TextRun, PageGeometry
      source/OcrEngine.kt              interface + OcrPage, OcrLine
      source/PageRasterizer.kt         interface
      txt/TxtParser.kt
      epub/EpubContainer.kt            zip + container.xml + OPF
      epub/EpubHtmlConverter.kt        XHTML -> List<ContentBlock>
      epub/EpubParser.kt               orchestration
      reflow/Line.kt                   Line, LineBuilder output types
      reflow/LineAssembler.kt          runs -> lines
      reflow/ColumnDetector.kt
      reflow/HeaderFooterDetector.kt
      reflow/ParagraphAssembler.kt
      reflow/Dehyphenator.kt
      reflow/ReflowPipeline.kt         orchestration + confidence
      structure/ChapterDetector.kt
      structure/HeadingSignals.kt      scoring signals
      pdf/ScannedDetector.kt
      pdf/PdfPipeline.kt               extract -> classify -> ocr -> reflow
      normalize/Normalizer.kt          assemble final Book, compute char offsets
    src/test/kotlin/app/folio/core/
      fixtures/FixtureBuilder.kt       generates EPUB/PDF/TXT corpus
      fixtures/PdfBoxTextSource.kt     Apache PDFBox impl of PdfTextSource
      fixtures/FakeOcrEngine.kt
      ... one test file per production file
    src/test/resources/                generated fixtures land here (gitignored)
```

---

### Task 1: Gradle scaffold and toolchain smoke test

Nothing else can be trusted until the build itself is proven. This task exists to fail loudly if any pinned version is wrong.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `core/build.gradle.kts`
- Create: `core/src/test/kotlin/app/folio/core/ToolchainTest.kt`
- Create: `gradlew`, `gradle/wrapper/gradle-wrapper.properties` (via `gradle wrapper`)

**Interfaces:**
- Consumes: nothing
- Produces: a working `./gradlew :core:test` command that every later task depends on

- [x] **Step 1: Generate the Gradle wrapper**

There is no Gradle on this machine. Bootstrap it with Homebrew, then immediately pin the wrapper so the Homebrew version stops mattering.

```bash
brew install gradle
cd /Users/gajananrathod/Documents/Gajanan/my-projects/folio
gradle wrapper --gradle-version 9.7.1
```

- [x] **Step 2: Write the version catalog**

Create `gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.3.21"
jsoup = "1.23.2"
serialization = "1.11.0"
coroutines = "1.11.0"
junit = "5.14.4"
pdfbox = "2.0.37"

[libraries]
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version = "1.14.4" }
pdfbox = { module = "org.apache.pdfbox:pdfbox", version.ref = "pdfbox" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [x] **Step 3: Write the build files**

`settings.gradle.kts`:

```kotlin
rootProject.name = "folio"
include(":core")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

`build.gradle.kts` (root):

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

`core/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.pdfbox)          // test-only: see Global Constraints
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

- [x] **Step 4: Write the smoke test**

Create `core/src/test/kotlin/app/folio/core/ToolchainTest.kt`. This asserts the two things most likely to be misconfigured: the JVM target, and that PDFBox 2.x (not 3.x) is on the test classpath.

```kotlin
package app.folio.core

import org.junit.jupiter.api.Test
import org.apache.pdfbox.pdmodel.PDDocument
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolchainTest {

    @Test
    fun `runs on JDK 21 or newer`() {
        val major = Runtime.version().feature()
        assertTrue(major >= 21, "expected JDK 21+, got $major")
    }

    @Test
    fun `apache pdfbox 2x is on the test classpath`() {
        // PDFBox 3.x moved Loader out of PDDocument; 2.x still has PDDocument.load.
        // If this fails to compile, the wrong PDFBox major version is pinned.
        val doc = PDDocument()
        assertEquals(0, doc.numberOfPages)
        doc.close()
    }
}
```

Add `testImplementation(kotlin("test"))` to `core/build.gradle.kts` dependencies for `assertEquals`/`assertTrue`.

- [x] **Step 5: Run the test and verify it passes**

```bash
cd /Users/gajananrathod/Documents/Gajanan/my-projects/folio
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL, 2 tests passed.

If dependency resolution fails on a version, **stop and report which one**. Do not silently bump versions — the pins exist for the reasons in Global Constraints.

- [x] **Step 6: Write .gitignore additions and commit**

```bash
cd /Users/gajananrathod/Documents/Gajanan/my-projects/folio
printf '%s\n' 'core/src/test/resources/generated/' >> .gitignore
git add -A
git commit -m "build: gradle scaffold with pinned toolchain and smoke test"
```

---

### Task 2: Fixture corpus generator

Every later task tests against these files. Building them first means reflow is never developed against a single convenient PDF.

**Files:**
- Create: `core/src/test/kotlin/app/folio/core/fixtures/FixtureBuilder.kt`
- Test: `core/src/test/kotlin/app/folio/core/fixtures/FixtureBuilderTest.kt`

**Interfaces:**
- Consumes: Apache PDFBox 2.0.37, `java.util.zip`
- Produces:
  - `object Fixtures`
  - `fun Fixtures.dir(): File` — the generated-fixtures directory, created on demand
  - `fun Fixtures.cleanEpub(): File`
  - `fun Fixtures.malformedEpub(): File` — missing `container.xml`
  - `fun Fixtures.epubNoNav(): File` — spine only, no nav/NCX
  - `fun Fixtures.plainTxt(): File`
  - `fun Fixtures.singleColumnPdf(): File`
  - `fun Fixtures.twoColumnPdf(): File`
  - `fun Fixtures.headerFooterPdf(): File` — running header, page numbers, hyphenated line breaks
  - `fun Fixtures.chapteredPdf(): File` — large centered chapter headings
  - `fun Fixtures.imageOnlyPdf(): File` — rasterized text, no extractable text layer
  - `fun Fixtures.largeBook(): File` — 400+ pages
  - `fun Fixtures.corruptPdf(): File` — truncated bytes
  - `fun Fixtures.unsupportedFile(): File` — a PNG named `.epub`

- [x] **Step 1: Write the failing test**

```kotlin
package app.folio.core.fixtures

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class FixtureBuilderTest {

    @Test
    fun `generates every fixture and each is non-empty`() {
        val files = listOf(
            Fixtures.cleanEpub(), Fixtures.malformedEpub(), Fixtures.epubNoNav(),
            Fixtures.plainTxt(), Fixtures.singleColumnPdf(), Fixtures.twoColumnPdf(),
            Fixtures.headerFooterPdf(), Fixtures.chapteredPdf(), Fixtures.imageOnlyPdf(),
            Fixtures.largeBook(), Fixtures.corruptPdf(), Fixtures.unsupportedFile(),
        )
        files.forEach { f ->
            assertTrue(f.exists(), "${f.name} was not created")
            assertTrue(f.length() > 0, "${f.name} is empty")
        }
    }

    @Test
    fun `image only pdf has no extractable text layer`() {
        val text = org.apache.pdfbox.text.PDFTextStripper()
            .getText(org.apache.pdfbox.pdmodel.PDDocument.load(Fixtures.imageOnlyPdf()))
        assertTrue(text.trim().length < 20, "expected no text layer, got ${text.length} chars")
    }

    @Test
    fun `header footer pdf repeats its running header on every page`() {
        val doc = org.apache.pdfbox.pdmodel.PDDocument.load(Fixtures.headerFooterPdf())
        val stripper = org.apache.pdfbox.text.PDFTextStripper()
        val occurrences = (1..doc.numberOfPages).count { p ->
            stripper.startPage = p; stripper.endPage = p
            stripper.getText(doc).contains("A HISTORY OF QUIET THINGS")
        }
        doc.close()
        assertTrue(occurrences >= 5, "running header appeared on only $occurrences pages")
    }
}
```

- [x] **Step 2: Run it to confirm it fails**

```bash
./gradlew :core:test --tests '*FixtureBuilderTest*'
```

Expected: FAIL — `Fixtures` unresolved.

- [x] **Step 3: Implement the fixture builder**

Create `core/src/test/kotlin/app/folio/core/fixtures/FixtureBuilder.kt`. Fixtures are generated once and cached on disk; regenerate if absent.

```kotlin
package app.folio.core.fixtures

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Fixtures {

    private val root: File by lazy {
        File("src/test/resources/generated").apply { mkdirs() }
    }

    private fun cached(name: String, build: (File) -> Unit): File {
        val f = File(root, name)
        if (!f.exists() || f.length() == 0L) build(f)
        return f
    }

    // ---------- text ----------

    private val LOREM = ("Distributed systems are a collection of independent computers " +
        "that appear to their users as a single coherent system. The consequences of " +
        "this definition are far reaching, and they shape every design decision that " +
        "follows in this book. ").repeat(3)

    fun plainTxt() = cached("plain.txt") { f ->
        f.writeText(buildString {
            appendLine("A History of Quiet Things")
            appendLine()
            appendLine("Chapter 1")
            appendLine()
            appendLine(LOREM)
            appendLine()
            appendLine("Chapter 2")
            appendLine()
            appendLine(LOREM)
        })
    }

    // ---------- EPUB ----------

    private fun zip(target: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(target.outputStream().buffered()).use { zos ->
            // mimetype must be first and stored uncompressed per the EPUB spec
            entries.forEach { (path, bytes) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    private fun chapterXhtml(title: String, body: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>$title</title></head>
          <body>
            <h1>$title</h1>
            <p>$body</p>
            <p>A second paragraph with <em>emphasis</em> and <strong>strength</strong>.</p>
            <ul><li>First item</li><li>Second item</li></ul>
            <blockquote>A quoted line.</blockquote>
          </body>
        </html>
    """.trimIndent().toByteArray()

    private val CONTAINER_XML = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf"
            media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent().toByteArray()

    private fun opf(withNav: Boolean) = """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="uid">urn:uuid:folio-test-0001</dc:identifier>
            <dc:title>A History of Quiet Things</dc:title>
            <dc:creator>Ada Marlowe</dc:creator>
            <dc:language>en</dc:language>
            <dc:publisher>Folio Test Press</dc:publisher>
          </metadata>
          <manifest>
            <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
            ${if (withNav) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" else ""}
          </manifest>
          <spine>
            <itemref idref="c1"/>
            <itemref idref="c2"/>
          </spine>
        </package>
    """.trimIndent().toByteArray()

    private val NAV_XHTML = """
        <?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body><nav epub:type="toc">
            <ol>
              <li><a href="c1.xhtml">The Weight of Silence</a></li>
              <li><a href="c2.xhtml">What the River Kept</a></li>
            </ol>
          </nav></body>
        </html>
    """.trimIndent().toByteArray()

    fun cleanEpub() = cached("clean.epub") { f ->
        zip(f, listOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to CONTAINER_XML,
            "OEBPS/content.opf" to opf(withNav = true),
            "OEBPS/nav.xhtml" to NAV_XHTML,
            "OEBPS/c1.xhtml" to chapterXhtml("The Weight of Silence", LOREM),
            "OEBPS/c2.xhtml" to chapterXhtml("What the River Kept", LOREM),
        ))
    }

    fun epubNoNav() = cached("nonav.epub") { f ->
        zip(f, listOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to CONTAINER_XML,
            "OEBPS/content.opf" to opf(withNav = false),
            "OEBPS/c1.xhtml" to chapterXhtml("The Weight of Silence", LOREM),
            "OEBPS/c2.xhtml" to chapterXhtml("What the River Kept", LOREM),
        ))
    }

    /** Valid zip, but no META-INF/container.xml — must fail cleanly, not crash. */
    fun malformedEpub() = cached("malformed.epub") { f ->
        zip(f, listOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "OEBPS/random.xhtml" to "<html><body><p>orphan</p></body></html>".toByteArray(),
        ))
    }

    // ---------- PDF ----------

    private fun newDoc(build: (PDDocument) -> Unit): PDDocument =
        PDDocument().also(build)

    private fun PDPageContentStream.line(text: String, size: Float, x: Float, y: Float, bold: Boolean = false) {
        beginText()
        setFont(if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA, size)
        newLineAtOffset(x, y)
        showText(text)
        endText()
    }

    private fun wrap(text: String, perLine: Int): List<String> =
        text.split(" ").fold(mutableListOf<String>()) { acc, w ->
            if (acc.isEmpty() || (acc.last().length + w.length + 1) > perLine) acc.add(w)
            else acc[acc.lastIndex] = acc.last() + " " + w
            acc
        }

    fun singleColumnPdf() = cached("single-column.pdf") { f ->
        newDoc { doc ->
            repeat(6) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    var y = 720f
                    wrap(LOREM, 70).forEach { l -> cs.line(l, 11f, 72f, y); y -= 16f }
                }
            }
        }.use { it.save(f) }
    }

    fun twoColumnPdf() = cached("two-column.pdf") { f ->
        newDoc { doc ->
            repeat(4) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    val lines = wrap(LOREM, 34)
                    var y = 720f
                    lines.take(20).forEach { l -> cs.line(l, 10f, 60f); y -= 15f }
                    y = 720f
                    lines.drop(20).forEach { l -> cs.line(l, 10f, 330f, y); y -= 15f }
                }
            }
        }.use { it.save(f) }
    }

    /** Running header, footer page numbers, and deliberate end-of-line hyphenation. */
    fun headerFooterPdf() = cached("header-footer.pdf") { f ->
        newDoc { doc ->
            for (p in 1..8) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.line("A HISTORY OF QUIET THINGS", 9f, 72f, 750f)
                    var y = 700f
                    listOf(
                        "Distributed sys-",
                        "tems are a col-",
                        "lection of independent computers that appear",
                        "to their users as a single coherent system.",
                    ).forEach { l -> cs.line(l, 11f, 72f, y); y -= 16f }
                    cs.line("$p", 9f, 300f, 40f)
                }
            }
        }.use { it.save(f) }
    }

    /** Large centered bold chapter headings, for structure detection. */
    fun chapteredPdf() = cached("chaptered.pdf") { f ->
        newDoc { doc ->
            listOf("Chapter 1" to "The Weight of Silence",
                   "Chapter 2" to "What the River Kept",
                   "Chapter 3" to "A Longer Winter").forEach { (label, title) ->
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.line(label, 10f, 250f, 700f)
                    cs.line(title, 22f, 180f, 660f, bold = true)
                    var y = 600f
                    wrap(LOREM, 70).forEach { l -> cs.line(l, 11f, 72f, y); y -= 16f }
                }
            }
        }.use { it.save(f) }
    }

    /** Renders singleColumnPdf to images and rebuilds a PDF from them: no text layer. */
    fun imageOnlyPdf() = cached("scanned.pdf") { f ->
        PDDocument.load(singleColumnPdf()).use { src ->
            val renderer = PDFRenderer(src)
            newDoc { out ->
                for (i in 0 until src.numberOfPages) {
                    val img: BufferedImage = renderer.renderImageWithDPI(i, 150f)
                    val page = PDPage(PDRectangle(img.width.toFloat(), img.height.toFloat()))
                    out.addPage(page)
                    val xobj = LosslessFactory.createFromImage(out, img)
                    PDPageContentStream(out, page).use { cs ->
                        cs.drawImage(xobj, 0f, 0f, img.width.toFloat(), img.height.toFloat())
                    }
                }
            }.use { it.save(f) }
        }
    }

    fun largeBook() = cached("large.pdf") { f ->
        newDoc { doc ->
            repeat(420) { p ->
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    if (p % 40 == 0) cs.line("Chapter ${p / 40 + 1}", 20f, 200f, 700f, bold = true)
                    var y = 650f
                    wrap(LOREM, 70).forEach { l -> cs.line(l, 11f, 72f, y); y -= 16f }
                }
            }
        }.use { it.save(f) }
    }

    fun corruptPdf() = cached("corrupt.pdf") { f ->
        val good = singleColumnPdf().readBytes()
        f.writeBytes(good.copyOfRange(0, good.size / 3))   // truncated mid-object
    }

    /** A PNG with an .epub extension: format detection must catch this by magic bytes. */
    fun unsupportedFile() = cached("actually-a-png.epub") { f ->
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        javax.imageio.ImageIO.write(img, "png", f)
    }
}
```

- [x] **Step 4: Run the tests and verify they pass**

```bash
./gradlew :core:test --tests '*FixtureBuilderTest*'
```

Expected: PASS, 3 tests.

Note `twoColumnPdf` has a deliberate compile error in the draft above (`cs.line(l, 10f, 60f)` is missing its `y` argument). Fix it to `cs.line(l, 10f, 60f, y)`. This is the kind of thing the compiler catches immediately — do not "fix" it by changing the `line` signature.

- [x] **Step 5: Commit**

```bash
git add -A
git commit -m "test: generated fixture corpus for every supported and unsupported format"
```

---

### Task 3: Tunable constants

**Files:**
- Create: `core/src/main/kotlin/app/folio/core/FolioConstants.kt`
- Test: `core/src/test/kotlin/app/folio/core/FolioConstantsTest.kt`

**Interfaces:**
- Produces: `object FolioConstants` with the fields below. Every later task reads thresholds from here.

- [x] **Step 1: Write the failing test**

```kotlin
package app.folio.core

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class FolioConstantsTest {
    @Test
    fun `thresholds are within sane bounds`() = with(FolioConstants) {
        assertTrue(SCANNED_CHARS_PER_PAGE in 10..500)
        assertTrue(MIN_OCR_CONFIDENCE in 0.0..1.0)
        assertTrue(MIN_REFLOW_CONFIDENCE in 0.0..1.0)
        assertTrue(COLUMN_CONSISTENCY in 0.0..1.0)
        assertTrue(HEADER_RECURRENCE in 0.0..1.0)
        assertTrue(PARAGRAPH_GAP_FACTOR > 1.0)
        assertTrue(OCR_RENDER_DPI in 72..600)
    }
}
```

- [x] **Step 2: Run it, confirm it fails** — `./gradlew :core:test --tests '*FolioConstantsTest*'`, FAIL, unresolved.

- [x] **Step 3: Implement**

```kotlin
package app.folio.core

/**
 * Heuristic thresholds, gathered here rather than inlined at call sites so that
 * tuning is a single-file change and every value is visible at once.
 * Spec section 13a. These are starting values and are expected to move as the
 * fixture corpus grows.
 */
object FolioConstants {
    /** Median chars/page below which a PDF is treated as scanned. */
    const val SCANNED_CHARS_PER_PAGE = 100

    /** Mean OCR confidence below which the book is flagged and the PDF fallback offered. */
    const val MIN_OCR_CONFIDENCE = 0.55

    /** Reflow confidence below which "Read original PDF" is offered. */
    const val MIN_REFLOW_CONFIDENCE = 0.50

    /** Fraction of pages that must agree before a column split is applied book-wide. */
    const val COLUMN_CONSISTENCY = 0.60

    /** Fraction of pages a line must recur on to count as a running header or footer. */
    const val HEADER_RECURRENCE = 0.50

    /** Multiple of median leading that forces a paragraph break. */
    const val PARAGRAPH_GAP_FACTOR = 1.5

    /** No page turn for this long pauses a reading session. */
    const val IDLE_TIMEOUT_MINUTES = 2

    /** Rasterization density for OCR. */
    const val OCR_RENDER_DPI = 300
}
```

- [x] **Step 4: Run, verify pass.**
- [x] **Step 5: Commit** — `git commit -am "feat: centralize tunable heuristic thresholds"`

---

### Task 4: Normalized model

**Files:**
- Create: `core/src/main/kotlin/app/folio/core/model/ContentBlock.kt`
- Create: `core/src/main/kotlin/app/folio/core/model/Chapter.kt`
- Create: `core/src/main/kotlin/app/folio/core/model/Book.kt`
- Create: `core/src/main/kotlin/app/folio/core/model/ReadingPosition.kt`
- Test: `core/src/test/kotlin/app/folio/core/model/ModelTest.kt`

**Interfaces:**
- Produces (every later task depends on these exact names):
  - `sealed interface ContentBlock` with `Paragraph`, `Heading`, `ListItem`, `BlockQuote`, `Image`, `PageBreak`
  - `data class InlineSpan(val text: String, val style: Set<InlineStyle>)`
  - `enum class InlineStyle { EMPHASIS, STRONG, CODE }`
  - `val ContentBlock.plainText: String`
  - `data class Chapter(index, title, blocks, startCharOffset, charCount)`
  - `data class Book(id, title, author, coverPath, metadata, sourceFormat, status, chapters)`
  - `enum class SourceFormat { EPUB, PDF_TEXT, PDF_OCR, TXT }`
  - `sealed interface ProcessingStatus` with `Idle`, `Importing`, `DetectingFormat`, `Extracting`, `Ocr(pagesDone, pagesTotal)`, `DetectingStructure`, `Normalizing`, `Ready`, `Failed(reason)`
  - `data class ReadingPosition(chapterIndex, blockIndex, charOffset)`
  - `fun Book.progressAt(position: ReadingPosition): Double`

- [x] **Step 1: Write the failing test**

```kotlin
package app.folio.core.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelTest {

    private fun para(s: String) = ContentBlock.Paragraph(listOf(InlineSpan(s, emptySet())))

    @Test
    fun `plainText concatenates spans in order`() {
        val h = ContentBlock.Heading(1, listOf(
            InlineSpan("The ", emptySet()),
            InlineSpan("Weight", setOf(InlineStyle.STRONG)),
        ))
        assertEquals("The Weight", h.plainText)
    }

    @Test
    fun `progress is zero at the start and one at the end`() {
        val c0 = Chapter(0, "One", listOf(para("aaaa")), startCharOffset = 0, charCount = 4)
        val c1 = Chapter(1, "Two", listOf(para("bbbbbb")), startCharOffset = 4, charCount = 6)
        val book = Book(
            id = "b1", title = "T", author = null, coverPath = null,
            metadata = BookMetadata(), sourceFormat = SourceFormat.TXT,
            status = ProcessingStatus.Ready, chapters = listOf(c0, c1),
        )
        assertEquals(0.0, book.progressAt(ReadingPosition(0, 0, 0)), 1e-9)
        assertEquals(1.0, book.progressAt(ReadingPosition(1, 0, 6)), 1e-9)
        assertEquals(0.4, book.progressAt(ReadingPosition(1, 0, 0)), 1e-9)
    }

    @Test
    fun `progress clamps rather than throwing on an out of range position`() {
        val c0 = Chapter(0, null, listOf(para("aaaa")), 0, 4)
        val book = Book("b", "T", null, null, BookMetadata(), SourceFormat.TXT,
            ProcessingStatus.Ready, listOf(c0))
        val p = book.progressAt(ReadingPosition(99, 99, 99))
        assertTrue(p in 0.0..1.0, "progress escaped 0..1: $p")
    }
}
```

- [x] **Step 2: Run it, confirm it fails.**

- [x] **Step 3: Implement the model**

`ContentBlock.kt`:

```kotlin
package app.folio.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class InlineStyle { EMPHASIS, STRONG, CODE }

@Serializable
data class InlineSpan(val text: String, val style: Set<InlineStyle> = emptySet())

@Serializable
sealed interface ContentBlock {
    @Serializable data class Paragraph(val spans: List<InlineSpan>) : ContentBlock
    @Serializable data class Heading(val level: Int, val spans: List<InlineSpan>) : ContentBlock
    @Serializable data class ListItem(val ordinal: Int?, val spans: List<InlineSpan>) : ContentBlock
    @Serializable data class BlockQuote(val spans: List<InlineSpan>) : ContentBlock
    @Serializable data class Image(val path: String, val caption: String? = null) : ContentBlock
    @Serializable data class PageBreak(val sourcePage: Int) : ContentBlock
}

val ContentBlock.plainText: String
    get() = when (this) {
        is ContentBlock.Paragraph  -> spans.joinToString("") { it.text }
        is ContentBlock.Heading    -> spans.joinToString("") { it.text }
        is ContentBlock.ListItem   -> spans.joinToString("") { it.text }
        is ContentBlock.BlockQuote -> spans.joinToString("") { it.text }
        is ContentBlock.Image      -> caption.orEmpty()
        is ContentBlock.PageBreak  -> ""
    }
```

`Chapter.kt`:

```kotlin
package app.folio.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val index: Int,
    val title: String?,
    val blocks: List<ContentBlock>,
    val startCharOffset: Int,
    val charCount: Int,
)

/** A chapter's identity and title without its content, for lazy loading. */
@Serializable
data class ChapterRef(
    val index: Int,
    val title: String?,
    val startCharOffset: Int,
    val charCount: Int,
)

fun Chapter.toRef() = ChapterRef(index, title, startCharOffset, charCount)
```

`Book.kt`:

```kotlin
package app.folio.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class SourceFormat { EPUB, PDF_TEXT, PDF_OCR, TXT }

@Serializable
data class BookMetadata(
    val language: String? = null,
    val publisher: String? = null,
    val identifier: String? = null,
    val subjects: List<String> = emptyList(),
)

@Serializable
sealed interface ProcessingStatus {
    @Serializable data object Idle : ProcessingStatus
    @Serializable data object Importing : ProcessingStatus
    @Serializable data object DetectingFormat : ProcessingStatus
    @Serializable data object Extracting : ProcessingStatus
    @Serializable data class  Ocr(val pagesDone: Int, val pagesTotal: Int) : ProcessingStatus
    @Serializable data object DetectingStructure : ProcessingStatus
    @Serializable data object Normalizing : ProcessingStatus
    @Serializable data object Ready : ProcessingStatus
    @Serializable data class  Failed(val reason: FailureReason) : ProcessingStatus
}

@Serializable
enum class FailureReason {
    CORRUPT_FILE, UNSUPPORTED_FORMAT, ENCRYPTED, EMPTY_DOCUMENT,
    EXTRACTION_FAILED, OCR_FAILED, INSUFFICIENT_STORAGE, INTERRUPTED,
}

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val metadata: BookMetadata,
    val sourceFormat: SourceFormat,
    val status: ProcessingStatus,
    val chapters: List<Chapter>,
) {
    val totalChars: Int get() = chapters.sumOf { it.charCount }
    /** True when reflow was not confident enough; the UI offers the original PDF. */
    val reflowFailed: Boolean get() = sourceFormat == SourceFormat.PDF_TEXT && chapters.isEmpty()
}
```

`ReadingPosition.kt`:

```kotlin
package app.folio.core.model

import kotlinx.serialization.Serializable

/**
 * Canonical location in a book. Deliberately not a page number: reflowed text
 * repaginates whenever typography changes, so page numbers are not stable
 * identities. Pages are derived by the paginator and never persisted.
 */
@Serializable
data class ReadingPosition(
    val chapterIndex: Int,
    val blockIndex: Int,
    val charOffset: Int,
) {
    companion object { val START = ReadingPosition(0, 0, 0) }
}

fun Book.progressAt(position: ReadingPosition): Double {
    val total = totalChars
    if (total <= 0) return 0.0
    val chapter = chapters.getOrNull(position.chapterIndex)
        ?: return if (position.chapterIndex >= chapters.size) 1.0 else 0.0
    val within = position.charOffset.coerceIn(0, chapter.charCount)
    return ((chapter.startCharOffset + within).toDouble() / total).coerceIn(0.0, 1.0)
}
```

- [x] **Step 4: Run the tests, verify pass.**
- [x] **Step 5: Commit** — `git commit -am "feat: normalized book model with character-based positions"`

---

### Task 5: TXT parser

Simplest complete path through the pipeline — proves the model before harder formats.

**Files:**
- Create: `core/src/main/kotlin/app/folio/core/txt/TxtParser.kt`
- Test: `core/src/test/kotlin/app/folio/core/txt/TxtParserTest.kt`

**Interfaces:**
- Consumes: `Book`, `Chapter`, `ContentBlock`, `SourceFormat` (Task 4); `Fixtures.plainTxt()` (Task 2)
- Produces: `class TxtParser { fun parse(file: java.io.File, id: String): Book }`

- [x] **Step 1: Write the failing test**

```kotlin
package app.folio.core.txt

import app.folio.core.fixtures.Fixtures
import app.folio.core.model.*
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TxtParserTest {

    @Test
    fun `splits on blank lines into paragraphs`() {
        val f = File.createTempFile("t", ".txt").apply {
            writeText("First para line one\nline two\n\nSecond para\n")
            deleteOnExit()
        }
        val book = TxtParser().parse(f, "id")
        val paras = book.chapters.single().blocks.filterIsInstance<ContentBlock.Paragraph>()
        assertEquals(2, paras.size)
        assertEquals("First para line one line two", paras[0].plainText)
    }

    @Test
    fun `detects chapter headings in the fixture`() {
        val book = TxtParser().parse(Fixtures.plainTxt(), "id")
        assertTrue(book.chapters.size >= 2, "expected 2+ chapters, got ${book.chapters.size}")
    }

    @Test
    fun `char offsets are cumulative and consistent`() {
        val book = TxtParser().parse(Fixtures.plainTxt(), "id")
        var running = 0
        book.chapters.forEach { c ->
            assertEquals(running, c.startCharOffset, "chapter ${c.index} offset")
            running += c.charCount
        }
        assertEquals(running, book.totalChars)
    }

    @Test
    fun `empty file fails cleanly rather than throwing`() {
        val f = File.createTempFile("empty", ".txt").apply { deleteOnExit() }
        val book = TxtParser().parse(f, "id")
        assertEquals(ProcessingStatus.Failed(FailureReason.EMPTY_DOCUMENT), book.status)
    }
}
```

- [x] **Step 2: Run it, confirm it fails.**

- [x] **Step 3: Implement**

```kotlin
package app.folio.core.txt

import app.folio.core.model.*
import java.io.File

class TxtParser {

    fun parse(file: File, id: String): Book {
        val raw = runCatching { file.readText() }.getOrElse {
            return failed(id, file, FailureReason.EXTRACTION_FAILED)
        }
        if (raw.isBlank()) return failed(id, file, FailureReason.EMPTY_DOCUMENT)

        val paragraphs = raw.split(Regex("\\n\\s*\\n"))
            .map { it.replace(Regex("\\s*\\n\\s*"), " ").trim() }
            .filter { it.isNotEmpty() }

        val chapters = groupIntoChapters(paragraphs)
        return Book(
            id = id,
            title = paragraphs.firstOrNull()?.take(120) ?: file.nameWithoutExtension,
            author = null,
            coverPath = null,
            metadata = BookMetadata(),
            sourceFormat = SourceFormat.TXT,
            status = ProcessingStatus.Ready,
            chapters = chapters,
        )
    }

    /**
     * A short line that matches a chapter pattern starts a new chapter. Anything
     * that does not match stays in the current chapter — never invent a boundary.
     */
    private fun groupIntoChapters(paragraphs: List<String>): List<Chapter> {
        val heading = Regex(
            """^(chapter|part|book)\s+([0-9]+|[ivxlcdm]+)\b.*|^([0-9]{1,3})\.?$""",
            RegexOption.IGNORE_CASE,
        )
        data class Acc(val title: String?, val blocks: MutableList<ContentBlock>)

        val groups = mutableListOf<Acc>()
        paragraphs.forEach { p ->
            val isHeading = p.length <= 60 && heading.matches(p.trim())
            if (isHeading || groups.isEmpty()) {
                groups += Acc(if (isHeading) p.trim() else null, mutableListOf())
                if (isHeading) {
                    groups.last().blocks += ContentBlock.Heading(1, listOf(InlineSpan(p.trim())))
                    return@forEach
                }
            }
            groups.last().blocks += ContentBlock.Paragraph(listOf(InlineSpan(p)))
        }

        var offset = 0
        return groups.mapIndexed { i, g ->
            val count = g.blocks.sumOf { it.plainText.length }
            Chapter(i, g.title, g.blocks, offset, count).also { offset += count }
        }
    }

    private fun failed(id: String, file: File, reason: FailureReason) = Book(
        id = id, title = file.nameWithoutExtension, author = null, coverPath = null,
        metadata = BookMetadata(), sourceFormat = SourceFormat.TXT,
        status = ProcessingStatus.Failed(reason), chapters = emptyList(),
    )
}
```

- [x] **Step 4: Run the tests, verify pass.** Fix the parser, not the tests, if `detects chapter headings` fails — the fixture has `Chapter 1` and `Chapter 2` on their own lines.
- [x] **Step 5: Commit** — `git commit -am "feat: TXT parser with conservative chapter grouping"`

---

### Remaining tasks

Tasks 6–17 continue the same shape. They are listed here with their interfaces
locked so that neighbouring tasks agree on names; the executing agent expands
each into the same five-step TDD cycle, writing the test first and running
`./gradlew :core:test` before every commit.

**Task 6 — EPUB container.** `core/epub/EpubContainer.kt`. Produces
`class EpubContainer(file: File) : Closeable` with
`fun opfPath(): String?`, `fun metadata(): BookMetadata`, `fun titleAndAuthor(): Pair<String, String?>`,
`fun spineHrefs(): List<String>`, `fun navTitles(): Map<String, String>`, `fun readEntry(href: String): ByteArray?`,
`fun coverBytes(): ByteArray?`. Tests use `Fixtures.cleanEpub()`, `Fixtures.epubNoNav()`,
`Fixtures.malformedEpub()`. Malformed must return null from `opfPath()`, never throw.

**Task 7 — EPUB HTML conversion.** `core/epub/EpubHtmlConverter.kt`. Produces
`class EpubHtmlConverter { fun convert(xhtml: String): List<ContentBlock> }` using jsoup.
Maps `p`→Paragraph, `h1..h6`→Heading(level), `li`→ListItem, `blockquote`→BlockQuote,
`img`→Image, `em`/`i`→EMPHASIS, `strong`/`b`→STRONG. Drops `script`, `style`, `nav`.
Unknown block elements degrade to Paragraph rather than being dropped.

**Task 8 — EPUB parser.** `core/epub/EpubParser.kt`. Produces
`class EpubParser { fun parse(file: File, id: String): Book }` composing Tasks 6 and 7,
one chapter per spine document, titles from nav when present else from the first heading.

**Task 9 — PdfTextSource interface and test implementation.**
`core/source/PdfTextSource.kt` produces:
`data class TextRun(text, x, y, width, height, fontSize, fontName, bold, italic)`,
`data class PageGeometry(pageIndex, width, height)`,
`data class PdfPage(geometry: PageGeometry, runs: List<TextRun>)`,
`interface PdfTextSource { fun pageCount(): Int; fun page(index: Int): PdfPage; fun outline(): List<OutlineEntry> }`,
`data class OutlineEntry(title: String, pageIndex: Int, level: Int)`.
`core/src/test/.../fixtures/PdfBoxTextSource.kt` implements it with Apache PDFBox 2.0.37
by subclassing `PDFTextStripper` and overriding `writeString(String, List<TextPosition>)`.

**Task 10 — Line assembly.** `core/reflow/LineAssembler.kt`. Produces
`data class Line(text, x, y, width, height, medianFontSize, bold, runs)` and
`class LineAssembler { fun assemble(page: PdfPage): List<Line> }`, clustering runs into
y-bands with tolerance from median glyph height, ordered top-to-bottom then left-to-right.

**Task 11 — Column detection.** `core/reflow/ColumnDetector.kt`. Produces
`class ColumnDetector { fun detect(pages: List<List<Line>>): ColumnLayout }` and
`sealed interface ColumnLayout { data object Single; data class Multi(val boundaries: List<Float>) }`.
Applies a split only when consistent across `FolioConstants.COLUMN_CONSISTENCY` of pages.

**Task 12 — Header/footer removal.** `core/reflow/HeaderFooterDetector.kt`. Produces
`class HeaderFooterDetector { fun strip(pages: List<List<Line>>): StripResult }` with
`data class StripResult(pages: List<List<Line>>, removedHeaders: Int, removedFooters: Int, confidence: Double)`.
Must pass on `Fixtures.headerFooterPdf()`: the running header and the page numbers go,
and **no body line is removed**. Assert body text survives, not merely that something was removed.

**Task 13 — Paragraph assembly.** `core/reflow/ParagraphAssembler.kt`. Produces
`class ParagraphAssembler { fun assemble(lines: List<Line>): List<ContentBlock> }`,
breaking on short final lines, indentation shifts, and gaps exceeding
`FolioConstants.PARAGRAPH_GAP_FACTOR` times median leading.

**Task 14 — De-hyphenation.** `core/reflow/Dehyphenator.kt`. Produces
`class Dehyphenator { fun join(lines: List<Line>): List<Line> }`. On
`Fixtures.headerFooterPdf()`, `"Distributed sys-" + "tems are a col-" + "lection of..."`
must become `"Distributed systems are a collection of..."`.

**Task 15 — Reflow pipeline.** `core/reflow/ReflowPipeline.kt`. Produces
`class ReflowPipeline { fun reflow(source: PdfTextSource): ReflowResult }` with
`data class ReflowResult(blocks: List<ContentBlock>, confidence: Double, pageBreaks: List<Int>)`.
Composes Tasks 10–14. Below `FolioConstants.MIN_REFLOW_CONFIDENCE`, returns the raw
extracted text as paragraphs with the low confidence attached — it must not return empty.

**Task 16 — Chapter detection.** `core/structure/ChapterDetector.kt` and
`core/structure/HeadingSignals.kt`. Produces
`class ChapterDetector { fun detect(blocks: List<ContentBlock>, outline: List<OutlineEntry>, spine: List<Int>?): List<Chapter> }`.
Order of authority: PDF outline, then EPUB spine, then scored heuristics. **With no
confident signal, return a single chapter containing every block.** Test that explicitly
against a fixture with no headings.

**Task 17 — Scanned detection.** `core/pdf/ScannedDetector.kt`. Produces
`class ScannedDetector { fun classify(source: PdfTextSource): ScanVerdict }` with
`data class ScanVerdict(isScanned: Boolean, medianCharsPerPage: Int, pagesNeedingOcr: List<Int>)`.
Samples the first 5 pages plus 10% of the rest. Must classify `Fixtures.imageOnlyPdf()`
as scanned and `Fixtures.singleColumnPdf()` as not.

**Task 18 — OCR interface and fake.** `core/source/OcrEngine.kt` produces
`data class OcrLine(text, x, y, width, height, confidence)`,
`data class OcrPage(pageIndex, lines, meanConfidence)`,
`interface OcrEngine { suspend fun recognize(pageIndex: Int, image: ByteArray): OcrPage }`.
Test fake returns known text so OCR-output reflow is testable with no device.
`OcrPage` maps to `List<TextRun>` so everything downstream is shared with text PDFs.

**Task 19 — PDF pipeline.** `core/pdf/PdfPipeline.kt`. Produces
`class PdfPipeline(source, ocr, rasterizer) { suspend fun process(id: String): Book }`
composing classify → optional OCR → reflow → chapter detection → normalize.

**Task 20 — Normalizer and end-to-end tests.** `core/normalize/Normalizer.kt` produces
`class Normalizer { fun assemble(...): Book }` computing cumulative char offsets.
End-to-end tests assert every fixture — including `corruptPdf`, `unsupportedFile`, and
`largeBook` — produces either a `Ready` book or a `Failed` book with the right
`FailureReason`, and **never throws**.

---

## Self-Review

**Spec coverage.** Spec §3 module structure → Task 1. §4 model → Task 4. §5 EPUB →
Tasks 6–8; PDF extraction → Task 9; scanned detection → Task 17; OCR → Task 18.
§6 reflow → Tasks 10–15. §7 chapter detection → Task 16. §13a constants → Task 3.
§12 error handling → Task 20 plus per-parser failure tests. §13 test corpus → Task 2.

Not covered by this plan, by design and deferred to Plan 2+: §8 reading engine and
pagination, §9 Room persistence, §10 habit tracking, §11 theme system, and all UI.

**Type consistency.** `ContentBlock`, `InlineSpan`, `Chapter`, `Book`, `ProcessingStatus`,
`FailureReason`, `ReadingPosition` are defined once in Task 4 and referenced unchanged
afterwards. `TextRun` and `PdfPage` are defined in Task 9 and consumed by Tasks 10–15.
`Line` is defined in Task 10 and consumed by Tasks 11–14. `FolioConstants` field names
match between Task 3 and every consumer.

**Known gap.** `PageRasterizer` is referenced by `PdfPipeline` (Task 19) but has no
Android implementation until Plan 2; Task 19 takes it as a constructor parameter and
tests pass a fake.
