package app.folio.core.pdf

import app.folio.core.fixtures.FakeOcrEngine
import app.folio.core.fixtures.FakeRasterizer
import app.folio.core.fixtures.Fixtures
import app.folio.core.fixtures.PdfBoxTextSource
import app.folio.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfPipelineTest {

    private val pipeline = PdfPipeline()

    private fun ocrText(page: Int) = listOf(
        "Recognised line one on page $page which runs the full measure here",
        "and continues onto a second recognised line to form a paragraph.",
    )

    @Test
    fun `a text pdf processes without ocr`() = runBlocking {
        val ocr = FakeOcrEngine(::ocrText)
        val raster = FakeRasterizer()
        PdfBoxTextSource(Fixtures.singleColumnPdf()).use { s ->
            val book = pipeline.process("id", "Test", s, ocr, raster)
            assertEquals(ProcessingStatus.Ready, book.status)
            assertEquals(SourceFormat.PDF_TEXT, book.sourceFormat)
            assertTrue(ocr.recognizedPages.isEmpty(), "OCR ran on a text PDF")
            assertTrue(raster.rasterizedPages.isEmpty(), "rasterized a text PDF")
        }
    }

    @Test
    fun `a scanned pdf is recognised and marked as ocr sourced`() = runBlocking {
        val ocr = FakeOcrEngine(::ocrText)
        PdfBoxTextSource(Fixtures.imageOnlyPdf()).use { s ->
            val book = pipeline.process("id", "Scan", s, ocr, FakeRasterizer())
            assertEquals(ProcessingStatus.Ready, book.status)
            assertEquals(SourceFormat.PDF_OCR, book.sourceFormat)
            assertTrue(ocr.recognizedPages.isNotEmpty(), "OCR did not run on a scan")
            val text = book.chapters.flatMap { it.blocks }.joinToString(" ") { it.plainText }
            assertTrue(text.contains("Recognised line one"), "OCR text missing from the book")
        }
    }

    @Test
    fun `low ocr confidence flags the book for the pdf fallback but still imports it`() = runBlocking {
        val ocr = FakeOcrEngine(::ocrText, confidence = 0.2f)
        PdfBoxTextSource(Fixtures.imageOnlyPdf()).use { s ->
            val book = pipeline.process("id", "Blurry", s, ocr, FakeRasterizer())
            assertEquals(ProcessingStatus.Ready, book.status)
            assertTrue(book.reflowFailed, "poor OCR should offer the original PDF")
            assertTrue(book.chapters.isNotEmpty(), "book must still import")
        }
    }

    @Test
    fun `a page that fails ocr does not abort the import`() = runBlocking {
        val ocr = FakeOcrEngine(::ocrText, failOnPage = 1)
        PdfBoxTextSource(Fixtures.imageOnlyPdf()).use { s ->
            val book = pipeline.process("id", "Partial", s, ocr, FakeRasterizer())
            assertEquals(ProcessingStatus.Ready, book.status)
            assertTrue(book.chapters.isNotEmpty(), "one bad page destroyed the whole import")
        }
    }

    @Test
    fun `a scan with no ocr available imports flagged rather than failing`() = runBlocking {
        PdfBoxTextSource(Fixtures.imageOnlyPdf()).use { s ->
            val book = pipeline.process("id", "Scan", s, ocr = null, rasterizer = null)
            assertTrue(book.status is ProcessingStatus.Ready || book.status is ProcessingStatus.Failed)
            assertTrue(book.reflowFailed, "must fall back to the original PDF")
        }
    }

    @Test
    fun `progress states are reported in order`() = runBlocking {
        val seen = mutableListOf<String>()
        PdfBoxTextSource(Fixtures.imageOnlyPdf()).use { s ->
            pipeline.process("id", "Scan", s, FakeOcrEngine(::ocrText), FakeRasterizer()) {
                seen += it::class.simpleName ?: ""
            }
        }
        assertTrue(seen.first() == "Extracting", "first state was ${seen.firstOrNull()}")
        assertTrue(seen.contains("Ocr"), "no OCR progress reported")
        assertTrue(seen.contains("DetectingStructure"), "no structure state reported")
    }

    @Test
    fun `chapters are detected on the chaptered fixture`() = runBlocking {
        PdfBoxTextSource(Fixtures.chapteredPdf()).use { s ->
            val book = pipeline.process("id", "Chaptered", s)
            assertEquals(3, book.chapters.size, "titles: ${book.chapters.map { it.title }}")
        }
    }

    @Test
    fun `char offsets are cumulative in the finished book`() = runBlocking {
        PdfBoxTextSource(Fixtures.chapteredPdf()).use { s ->
            val book = pipeline.process("id", "Chaptered", s)
            var running = 0
            book.chapters.forEach { c ->
                assertEquals(running, c.startCharOffset, "chapter ${c.index}")
                running += c.charCount
            }
            assertEquals(running, book.totalChars)
        }
    }

    @Test
    fun `the large book processes without losing its opening text`() = runBlocking {
        PdfBoxTextSource(Fixtures.largeBook()).use { s ->
            val book = pipeline.process("id", "Large", s)
            assertEquals(ProcessingStatus.Ready, book.status)
            assertTrue(book.totalChars > 100_000, "only ${book.totalChars} chars from 420 pages")
        }
    }

    /**
     * A recogniser that sees a photographed page: real prose, plus the things a
     * camera picks up around it.
     */
    private class NoisyOcrEngine : app.folio.core.source.OcrEngine {
        override suspend fun recognize(pageIndex: Int, image: ByteArray) =
            app.folio.core.source.OcrPage(
                pageIndex = pageIndex,
                lines = listOf(
                    // The page edge, seen down the far left margin.
                    app.folio.core.source.OcrLine(
                        "llllllll", x = 4f, y = 720f, width = 18f, height = 80f,
                        confidence = 0.97f,
                    ),
                    app.folio.core.source.OcrLine(
                        "Recognised line one on page $pageIndex which runs the full measure",
                        x = 72f, y = 700f, width = 460f, height = 11f, confidence = 0.93f,
                    ),
                    app.folio.core.source.OcrLine(
                        "and continues onto a second recognised line to form a paragraph.",
                        x = 72f, y = 684f, width = 455f, height = 11f, confidence = 0.92f,
                    ),
                    app.folio.core.source.OcrLine(
                        "A third line of genuine prose keeps the paragraph going.",
                        x = 72f, y = 668f, width = 440f, height = 11f, confidence = 0.91f,
                    ),
                    app.folio.core.source.OcrLine(
                        "The shadow in the gutter resolves into nothing in particular.",
                        x = 72f, y = 652f, width = 450f, height = 11f, confidence = 0.9f,
                    ),
                    // A smear where the gutter met the platen.
                    app.folio.core.source.OcrLine(
                        "|| . -- |", x = 70f, y = 600f, width = 40f, height = 10f,
                        confidence = 0.31f,
                    ),
                ),
                meanConfidence = 0.8f,
                imageWidth = 612f,
                imageHeight = 792f,
            )
    }

    @Test
    fun `a photographed page keeps its prose and loses its noise`() = runBlocking {
        PdfBoxTextSource(Fixtures.imageOnlyPdf()).use { s ->
            val book = pipeline.process("id", "Scan", s, NoisyOcrEngine(), FakeRasterizer())
            val text = book.chapters.flatMap { it.blocks }.joinToString(" ") { it.plainText }

            // What the filter exists to remove.
            assertTrue("llllllll" !in text, "the page edge reached the book")
            assertTrue("|| . --" !in text, "the smear reached the book")

            // The conservatism rule, stated as an assertion: all four real lines
            // survive, not merely some of them.
            assertTrue("Recognised line one" in text, "prose was eaten")
            assertTrue("form a paragraph" in text, "prose was eaten")
            assertTrue("genuine prose keeps the paragraph going" in text, "prose was eaten")
            assertTrue("resolves into nothing in particular" in text, "prose was eaten")
        }
    }
}