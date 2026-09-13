package app.quire.core

import app.quire.core.epub.EpubParser
import app.quire.core.fixtures.Fixtures
import app.quire.core.fixtures.PdfBoxTextSource
import app.quire.core.model.*
import app.quire.core.pdf.PdfPipeline
import app.quire.core.txt.TxtParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every fixture, through the whole pipeline. The contract under test is that a book
 * either arrives Ready or fails with a reason — and that nothing, however broken,
 * throws.
 */
class EndToEndTest {

    private fun processPdf(file: File): Book = runBlocking {
        PdfBoxTextSource(file).use { s ->
            PdfPipeline().process(id = "id", title = file.nameWithoutExtension, source = s)
        }
    }

    // ---------------------------------------------------------------- formats

    @Test
    fun `format detection reads signatures, not extensions`() {
        assertEquals(SourceFormat.PDF_TEXT, FormatDetector.detect(Fixtures.singleColumnPdf()))
        assertEquals(SourceFormat.EPUB, FormatDetector.detect(Fixtures.cleanEpub()))
        assertEquals(SourceFormat.TXT, FormatDetector.detect(Fixtures.plainTxt()))
        // A PNG named .epub must be rejected at the door.
        assertNull(FormatDetector.detect(Fixtures.unsupportedFile()))
    }

    // ------------------------------------------------------------- happy paths

    @Test
    fun `epub imports ready with chapters`() {
        val book = EpubParser().parse(Fixtures.cleanEpub(), "id")
        assertEquals(ProcessingStatus.Ready, book.status)
        assertTrue(book.chapters.isNotEmpty())
        assertTrue(book.totalChars > 0)
    }

    @Test
    fun `txt imports ready with chapters`() {
        val book = TxtParser().parse(Fixtures.plainTxt(), "id")
        assertEquals(ProcessingStatus.Ready, book.status)
        assertTrue(book.chapters.size >= 2)
    }

    @Test
    fun `every readable pdf fixture imports ready`() {
        listOf(
            Fixtures.singleColumnPdf(), Fixtures.twoColumnPdf(),
            Fixtures.headerFooterPdf(), Fixtures.chapteredPdf(),
            Fixtures.imageOnlyPdf(), Fixtures.largeBook(),
        ).forEach { f ->
            val book = processPdf(f)
            assertEquals(ProcessingStatus.Ready, book.status, "${f.name} did not import")
        }
    }

    @Test
    fun `a pdf with text is reflowed and a scan is not`() {
        // The line the whole pipeline turns on. A PDF carrying a text layer becomes
        // a reflowable book; a scan becomes a book read as its own pages, with no
        // text at all rather than text that might be wrong.
        listOf(
            Fixtures.singleColumnPdf(), Fixtures.twoColumnPdf(),
            Fixtures.headerFooterPdf(), Fixtures.chapteredPdf(), Fixtures.largeBook(),
        ).forEach { f ->
            val book = processPdf(f)
            assertTrue(book.totalChars > 0, "${f.name} produced no text")
            assertEquals(SourceFormat.PDF_TEXT, book.sourceFormat, f.name)
        }

        val scan = processPdf(Fixtures.imageOnlyPdf())
        assertEquals(SourceFormat.PDF_SCANNED, scan.sourceFormat)
        assertEquals(0, scan.totalChars, "a scan invented text")
        assertTrue(scan.reflowFailed, "a scan must route to its original pages")
    }

    // ------------------------------------------------------------ failure paths

    @Test
    fun `a corrupt pdf fails cleanly instead of throwing`() {
        val result = runCatching { processPdf(Fixtures.corruptPdf()) }
        // Either the source refuses to open, or the pipeline reports a failure.
        // What must never happen is an unhandled crash reaching the caller.
        if (result.isSuccess) {
            assertTrue(result.getOrThrow().status is ProcessingStatus.Failed)
        } else {
            assertTrue(result.exceptionOrNull() is java.io.IOException,
                "expected an IO failure, got ${result.exceptionOrNull()}")
        }
    }

    @Test
    fun `a malformed epub fails with a corrupt file reason`() {
        val book = EpubParser().parse(Fixtures.malformedEpub(), "id")
        assertEquals(ProcessingStatus.Failed(FailureReason.CORRUPT_FILE), book.status)
    }

    @Test
    fun `an unsupported file never reaches a parser`() {
        assertNull(FormatDetector.detect(Fixtures.unsupportedFile()))
        // And if one is forced through anyway, it still fails rather than crashing.
        assertTrue(EpubParser().parse(Fixtures.unsupportedFile(), "id").status
            is ProcessingStatus.Failed)
    }

    @Test
    fun `an empty file fails with an empty document reason`() {
        val empty = File.createTempFile("empty", ".txt").apply { deleteOnExit() }
        assertEquals(
            ProcessingStatus.Failed(FailureReason.EMPTY_DOCUMENT),
            TxtParser().parse(empty, "id").status,
        )
    }

    @Test
    fun `a truncated pdf imports but reports less content than the intact file`() {
        // Pinned hazard: PDFBox recovers a truncated file and it looks healthy.
        val whole = processPdf(Fixtures.singleColumnPdf())
        val partial = processPdf(Fixtures.truncatedPdf())
        assertTrue(partial.totalChars < whole.totalChars,
            "truncated file yielded ${partial.totalChars} vs ${whole.totalChars} — no shortfall")
    }

    // ---------------------------------------------------------- reader contract

    @Test
    fun `every imported book satisfies the reader's position invariants`() {
        val books = listOf(
            EpubParser().parse(Fixtures.cleanEpub(), "e"),
            TxtParser().parse(Fixtures.plainTxt(), "t"),
            processPdf(Fixtures.chapteredPdf()),
            processPdf(Fixtures.imageOnlyPdf()),
        )
        books.forEach { book ->
            var running = 0
            book.chapters.forEach { c ->
                assertEquals(running, c.startCharOffset, "${book.title}: chapter ${c.index}")
                assertTrue(c.charCount >= 0)
                running += c.charCount
            }
            assertEquals(running, book.totalChars, "${book.title}: totalChars mismatch")
            assertEquals(0.0, book.progressAt(ReadingPosition.START), 1e-9)

            val last = book.chapters.lastOrNull()
            if (last != null) {
                val end = ReadingPosition(last.index, 0, last.charCount)
                assertEquals(1.0, book.progressAt(end), 1e-9, "${book.title}: end is not 100%")
            }
        }
    }
}
