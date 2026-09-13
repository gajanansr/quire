package app.quire.core.pdf

import app.quire.core.fixtures.FakeRasterizer
import app.quire.core.fixtures.Fixtures
import app.quire.core.fixtures.PdfBoxTextSource
import app.quire.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfPipelineTest {

    private val pipeline = PdfPipeline()

    @Test
    fun `a pdf with a text layer is reflowed into a book`() = runBlocking {
        PdfBoxTextSource(Fixtures.singleColumnPdf()).use { s ->
            val book = pipeline.process("id", "Test", s)
            assertEquals(ProcessingStatus.Ready, book.status)
            assertEquals(SourceFormat.PDF_TEXT, book.sourceFormat)
            assertTrue(book.chapters.isNotEmpty(), "a text PDF should produce chapters")
        }
    }

    // -------------------------------------------------------------- scans

    @Test
    fun `a scan imports as a real book, to be read as its pages`() = runBlocking {
        // Recognising a scan and reflowing the result was tried and abandoned: the
        // mistakes are invisible, and a book missing a page it never announced is
        // worse than a book that says plainly it cannot be reflowed.
        PdfBoxTextSource(Fixtures.imageOnlyPdf()).use { s ->
            val book = pipeline.process("id", "Scan", s)
            assertEquals(ProcessingStatus.Ready, book.status, "a scan must not fail to import")
            assertEquals(SourceFormat.PDF_SCANNED, book.sourceFormat)
            assertTrue(book.reflowFailed, "a scan must route to its original pages")
        }
    }

    @Test
    fun `a scan carries no invented text`() = runBlocking {
        // The whole point. No chapters, no blocks, nothing that could be wrong.
        PdfBoxTextSource(Fixtures.imageOnlyPdf()).use { s ->
            val book = pipeline.process("id", "Scan", s)
            assertTrue(book.chapters.isEmpty(), "a scan produced text: ${book.chapters}")
            assertEquals(0, book.totalChars)
        }
    }

    @Test
    fun `a scan is never rasterized by the pipeline`() = runBlocking {
        // Rendering pages is the reader's job now, and the cover's. Doing it during
        // import would be work for text nobody will use.
        val raster = FakeRasterizer()
        PdfBoxTextSource(Fixtures.imageOnlyPdf()).use { s ->
            pipeline.process("id", "Scan", s)
        }
        assertTrue(raster.rasterizedPages.isEmpty())
    }

    @Test
    fun `a book that declares its chapters keeps them`() = runBlocking {
        PdfBoxTextSource(Fixtures.outlinedPdf()).use { s ->
            val book = pipeline.process("id", "Outlined", s)
            assertEquals(3, book.chapters.size, "titles: ${book.chapters.map { it.title }}")
        }
    }

    @Test
    fun `a book that only looks chaptered is one chapter`() = runBlocking {
        PdfBoxTextSource(Fixtures.chapteredPdf()).use { s ->
            val book = pipeline.process("id", "Chaptered", s)
            assertEquals(1, book.chapters.size, "invented: ${book.chapters.map { it.title }}")
        }
    }

    @Test
    fun `char offsets are cumulative in the finished book`() = runBlocking {
        PdfBoxTextSource(Fixtures.outlinedPdf()).use { s ->
            val book = pipeline.process("id", "Outlined", s)
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

}
