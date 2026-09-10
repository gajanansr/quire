package app.folio.core.reflow

import app.folio.core.fixtures.Fixtures
import app.folio.core.fixtures.PdfBoxTextSource
import app.folio.core.source.PageGeometry
import app.folio.core.source.PdfPage
import app.folio.core.source.TextRun
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeaderFooterDetectorTest {

    private val detector = HeaderFooterDetector()
    private val assembler = LineAssembler()

    private fun run(text: String, y: Float, x: Float = 72f) = TextRun(
        text = text, x = x, y = y, width = text.length * 5.5f, height = 11f,
        fontSize = 11f, fontName = "Helvetica", bold = false, italic = false,
    )

    /** Pages with a running header, a page number, and unique body text. */
    private fun pages(count: Int, header: String? = "RUNNING HEADER", footer: Boolean = true) =
        (0 until count).map { p ->
            val runs = mutableListOf<TextRun>()
            if (header != null) runs += run(header, 750f)
            runs += run("Body line one on page $p", 700f)
            runs += run("Body line two on page $p", 684f)
            if (footer) runs += run("${p + 1}", 40f, x = 300f)
            PdfPage(PageGeometry(p, 612f, 792f), runs)
        }

    private fun linesOf(pages: List<PdfPage>) = pages.map { assembler.assemble(it) }
    private fun heightsOf(pages: List<PdfPage>) = pages.map { it.geometry.height }

    private fun stripped(pages: List<PdfPage>) =
        detector.strip(linesOf(pages), heightsOf(pages))

    @Test
    fun `a repeated running header is removed`() {
        val result = stripped(pages(8))
        assertTrue(result.pages.flatten().none { it.text.contains("RUNNING HEADER") },
            "running header survived")
        assertEquals(8, result.removedHeaders)
    }

    @Test
    fun `page numbers are removed`() {
        val result = stripped(pages(8))
        assertTrue(result.pages.flatten().none { it.text.trim().matches(Regex("\\d+")) },
            "a bare page number survived")
    }

    @Test
    fun `body text is never removed`() {
        val result = stripped(pages(8))
        val body = result.pages.flatten().map { it.text }
        (0 until 8).forEach { p ->
            assertTrue(body.any { it.contains("Body line one on page $p") },
                "body line one lost on page $p")
            assertTrue(body.any { it.contains("Body line two on page $p") },
                "body line two lost on page $p")
        }
    }

    @Test
    fun `a header appearing on only a couple of pages is kept`() {
        // Below the recurrence threshold this is content, not furniture.
        val withHeader = pages(2, header = "OCCASIONAL NOTE")
        val without = pages(8, header = null).map { it.copy() }
        val result = stripped(withHeader + without)
        assertTrue(result.pages.flatten().any { it.text.contains("OCCASIONAL NOTE") },
            "an infrequent line must be treated as content")
    }

    @Test
    fun `a single page document loses nothing`() {
        // With one page every line "recurs on 100% of pages" — the naive rule would
        // delete the whole document.
        val result = stripped(pages(1))
        assertEquals(0, result.removedHeaders)
        assertEquals(0, result.removedFooters)
        assertTrue(result.pages.flatten().any { it.text.contains("Body line one") })
    }

    @Test
    fun `headers differing only by page number still count as the same header`() {
        val varying = (0 until 8).map { p ->
            PdfPage(PageGeometry(p, 612f, 792f), listOf(
                run("Chapter 3 — page ${p + 1}", 750f),
                run("Body on page $p", 700f),
            ))
        }
        val result = stripped(varying)
        assertTrue(result.pages.flatten().none { it.text.contains("Chapter 3 —") },
            "header varying only by number was not recognised")
    }

    @Test
    fun `a line in the middle of the page is never treated as furniture`() {
        val repeatedBody = (0 until 8).map { p ->
            PdfPage(PageGeometry(p, 612f, 792f), listOf(
                run("This exact sentence repeats mid-page.", 400f),
                run("Body on page $p", 380f),
            ))
        }
        val result = stripped(repeatedBody)
        assertTrue(result.pages.flatten().any { it.text.contains("This exact sentence repeats") },
            "a repeated mid-page line is content, not a header")
    }

    @Test
    fun `confidence is high when furniture is found and neutral when none is`() {
        val withFurniture = stripped(pages(8))
        val clean = stripped(pages(8, header = null, footer = false))
        assertTrue(withFurniture.confidence >= clean.confidence)
        assertTrue(clean.confidence in 0.0..1.0)
    }

    @Test
    fun `strips the header footer fixture without touching body text`() {
        PdfBoxTextSource(Fixtures.headerFooterPdf()).use { s ->
            val pages = (0 until s.pageCount()).map { s.page(it) }
            val all = pages.map { assembler.assemble(it) }
            val result = detector.strip(all, pages.map { it.geometry.height })
            val text = result.pages.flatten().joinToString(" ") { it.text }

            assertTrue(!text.contains("A HISTORY OF QUIET THINGS"), "running header survived")
            assertTrue(result.pages.flatten().none { it.text.trim().matches(Regex("\\d+")) },
                "page number survived")
            assertTrue(text.contains("lection of independent computers"),
                "body text was destroyed while removing furniture")
            assertTrue(text.contains("Distributed sys-"), "first body line lost")
        }
    }
}
