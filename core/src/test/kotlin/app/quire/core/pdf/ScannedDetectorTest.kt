package app.quire.core.pdf

import app.quire.core.fixtures.Fixtures
import app.quire.core.fixtures.PdfBoxTextSource
import app.quire.core.source.OutlineEntry
import app.quire.core.source.PageGeometry
import app.quire.core.source.PdfPage
import app.quire.core.source.PdfTextSource
import app.quire.core.source.TextRun
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScannedDetectorTest {

    private val detector = ScannedDetector()

    private class FakeSource(private val pages: List<PdfPage>) : PdfTextSource {
        var pagesRead = 0; private set
        override fun pageCount() = pages.size
        override fun page(index: Int): PdfPage { pagesRead++; return pages[index] }
        override fun outline(): List<OutlineEntry> = emptyList()
        override fun isEncrypted() = false
        override fun close() {}
    }

    private fun textPage(i: Int, chars: Int = 400) = PdfPage(
        PageGeometry(i, 612f, 792f),
        listOf(TextRun("x".repeat(chars), 72f, 700f, 400f, 11f, 11f, "Helvetica", false, false)),
    )

    private fun blankPage(i: Int) = PdfPage(PageGeometry(i, 612f, 792f), emptyList())

    @Test
    fun `a text pdf is not scanned`() {
        PdfBoxTextSource(Fixtures.singleColumnPdf()).use { s ->
            val v = detector.classify(s)
            assertTrue(!v.isScanned, "text PDF misread as scanned (median ${v.medianCharsPerPage})")
            assertTrue(v.pagesNeedingOcr.isEmpty())
        }
    }

    @Test
    fun `an image only pdf is scanned and every page needs ocr`() {
        PdfBoxTextSource(Fixtures.imageOnlyPdf()).use { s ->
            val v = detector.classify(s)
            assertTrue(v.isScanned, "scan not detected (median ${v.medianCharsPerPage})")
            assertEquals(s.pageCount(), v.pagesNeedingOcr.size)
        }
    }

    @Test
    fun `a mostly scanned book lists only the pages actually lacking text`() {
        val pages = listOf(blankPage(0), blankPage(1), textPage(2), blankPage(3), blankPage(4))
        val v = detector.classify(FakeSource(pages))
        assertTrue(v.isScanned)
        assertEquals(listOf(0, 1, 3, 4), v.pagesNeedingOcr)
    }

    @Test
    fun `a healthy book does not schedule ocr even with an occasional plate`() {
        // The median decides whether to bother at all; a few image pages in an
        // otherwise text book are plates, not a scan.
        val pages = (0 until 20).map { if (it == 7) blankPage(it) else textPage(it) }
        val v = detector.classify(FakeSource(pages))
        assertTrue(!v.isScanned)
        assertTrue(v.pagesNeedingOcr.isEmpty())
    }

    @Test
    fun `classification samples rather than reading every page of a large book`() {
        val pages = (0 until 400).map { textPage(it) }
        val source = FakeSource(pages)
        detector.classify(source)
        assertTrue(source.pagesRead < 100,
            "read ${source.pagesRead} of 400 pages; classification should sample")
    }

    @Test
    fun `a scanned book still enumerates all its pages for ocr`() {
        val pages = (0 until 60).map { blankPage(it) }
        val v = detector.classify(FakeSource(pages))
        assertTrue(v.isScanned)
        assertEquals(60, v.pagesNeedingOcr.size, "every blank page must be queued for OCR")
    }

    @Test
    fun `an empty document is not classified as scanned`() {
        val v = detector.classify(FakeSource(emptyList()))
        assertTrue(!v.isScanned)
        assertTrue(v.pagesNeedingOcr.isEmpty())
    }

    @Test
    fun `the large book fixture is not mistaken for a scan`() {
        PdfBoxTextSource(Fixtures.largeBook()).use { s ->
            assertTrue(!detector.classify(s).isScanned)
        }
    }
}
