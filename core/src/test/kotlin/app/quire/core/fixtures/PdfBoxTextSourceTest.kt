package app.quire.core.fixtures

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfBoxTextSourceTest {

    @Test
    fun `reports the page count`() {
        PdfBoxTextSource(Fixtures.singleColumnPdf()).use { s ->
            assertEquals(6, s.pageCount())
        }
    }

    @Test
    fun `extracts positioned runs with font metrics`() {
        PdfBoxTextSource(Fixtures.singleColumnPdf()).use { s ->
            val runs = s.page(0).runs
            assertTrue(runs.isNotEmpty(), "no runs extracted")
            assertTrue(runs.all { it.text.isNotEmpty() })
            assertTrue(runs.all { it.fontSize > 0f }, "font size missing")
            assertTrue(runs.all { it.height > 0f }, "glyph height missing")
            assertTrue(runs.any { it.text.contains("Distributed") })
        }
    }

    @Test
    fun `page geometry matches US Letter`() {
        PdfBoxTextSource(Fixtures.singleColumnPdf()).use { s ->
            val g = s.page(0).geometry
            assertEquals(612f, g.width, 1f)
            assertEquals(792f, g.height, 1f)
        }
    }

    @Test
    fun `two column pdf yields runs on both sides of the gutter`() {
        PdfBoxTextSource(Fixtures.twoColumnPdf()).use { s ->
            val xs = s.page(0).runs.map { it.x }
            assertTrue(xs.any { it < 200f }, "no left-column runs")
            assertTrue(xs.any { it > 300f }, "no right-column runs")
        }
    }

    @Test
    fun `chaptered pdf exposes a larger font size for the chapter title`() {
        PdfBoxTextSource(Fixtures.chapteredPdf()).use { s ->
            val runs = s.page(0).runs
            val bodyMedian = runs.map { it.fontSize }.sorted()[runs.size / 2]
            val title = runs.firstOrNull { it.text.contains("Weight of Silence") }
            assertTrue(title != null, "title run not found")
            assertTrue(title.fontSize > bodyMedian * 1.5f,
                "title ${title.fontSize} not clearly larger than body $bodyMedian")
        }
    }

    @Test
    fun `bold is detected from the font name`() {
        PdfBoxTextSource(Fixtures.chapteredPdf()).use { s ->
            val title = s.page(0).runs.first { it.text.contains("Weight of Silence") }
            assertTrue(title.bold, "bold title not flagged, font was ${title.fontName}")
        }
    }

    @Test
    fun `scanned pdf yields effectively no runs`() {
        PdfBoxTextSource(Fixtures.imageOnlyPdf()).use { s ->
            assertTrue(s.page(0).charCount < 20, "expected no text layer")
        }
    }

    @Test
    fun `a structurally corrupt pdf fails on open rather than producing garbage`() {
        val result = runCatching { PdfBoxTextSource(Fixtures.corruptPdf()).use { it.pageCount() } }
        assertTrue(result.isFailure, "expected corrupt PDF to fail loudly on open")
    }

    /**
     * Documents a real hazard rather than asserting a wish: PDFBox's lenient parser
     * rebuilds a truncated file by scanning for objects, so it opens cleanly and
     * looks healthy. Detecting the shortfall is the pipeline's job, and this test
     * exists so that behaviour is pinned and visible.
     */
    @Test
    fun `a truncated pdf opens successfully with partial content`() {
        PdfBoxTextSource(Fixtures.truncatedPdf()).use { s ->
            assertTrue(s.pageCount() > 0, "expected lenient recovery to yield pages")
            val recovered = (0 until s.pageCount()).sumOf { s.page(it).charCount }
            PdfBoxTextSource(Fixtures.singleColumnPdf()).use { full ->
                val whole = (0 until full.pageCount()).sumOf { full.page(it).charCount }
                assertTrue(
                    recovered < whole,
                    "truncated file recovered $recovered of $whole chars — expected a shortfall",
                )
            }
        }
    }
}
