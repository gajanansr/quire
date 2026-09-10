package app.folio.core.reflow

import app.folio.core.fixtures.Fixtures
import app.folio.core.fixtures.PdfBoxTextSource
import app.folio.core.source.PageGeometry
import app.folio.core.source.PdfPage
import app.folio.core.source.TextRun
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColumnDetectorTest {

    private val detector = ColumnDetector()

    private fun run(text: String, x: Float, y: Float, w: Float = 200f) = TextRun(
        text = text, x = x, y = y, width = w, height = 11f,
        fontSize = 11f, fontName = "Helvetica", bold = false, italic = false,
    )

    /** A page with text in two bands separated by an empty gutter around x=300. */
    private fun twoColumnPage(index: Int = 0): PdfPage {
        val runs = mutableListOf<TextRun>()
        var y = 700f
        repeat(20) {
            runs += run("left text here", 60f, y, 200f)
            runs += run("right text here", 330f, y, 200f)
            y -= 15f
        }
        return PdfPage(PageGeometry(index, 612f, 792f), runs)
    }

    private fun singleColumnPage(index: Int = 0): PdfPage {
        val runs = mutableListOf<TextRun>()
        var y = 700f
        repeat(20) { runs += run("full width line of body text", 72f, y, 460f); y -= 16f }
        return PdfPage(PageGeometry(index, 612f, 792f), runs)
    }

    @Test
    fun `a consistently two column document is detected as multi column`() {
        val layout = detector.detect((0 until 4).map { twoColumnPage(it) })
        assertTrue(layout is ColumnLayout.Multi, "expected Multi, got $layout")
        val gutter = (layout as ColumnLayout.Multi).boundaries.single()
        assertTrue(gutter in 260f..330f, "gutter at $gutter, expected ~300")
    }

    @Test
    fun `a single column document is detected as single`() {
        assertEquals(ColumnLayout.Single, detector.detect((0 until 4).map { singleColumnPage(it) }))
    }

    @Test
    fun `one odd two column page does not make the whole book two column`() {
        // Conservatism: a single stray page must not restructure the entire document.
        val pages = listOf(twoColumnPage(0)) + (1 until 6).map { singleColumnPage(it) }
        assertEquals(ColumnLayout.Single, detector.detect(pages))
    }

    @Test
    fun `an empty document is single column rather than an error`() {
        assertEquals(ColumnLayout.Single, detector.detect(emptyList()))
        assertEquals(
            ColumnLayout.Single,
            detector.detect(listOf(PdfPage(PageGeometry(0, 612f, 792f), emptyList()))),
        )
    }

    @Test
    fun `the two column fixture is detected as two column`() {
        PdfBoxTextSource(Fixtures.twoColumnPdf()).use { s ->
            val pages = (0 until s.pageCount()).map { s.page(it) }
            assertTrue(detector.detect(pages) is ColumnLayout.Multi)
        }
    }

    @Test
    fun `the single column fixture is not mistaken for two column`() {
        PdfBoxTextSource(Fixtures.singleColumnPdf()).use { s ->
            val pages = (0 until s.pageCount()).map { s.page(it) }
            assertEquals(ColumnLayout.Single, detector.detect(pages))
        }
    }

    @Test
    fun `assembling with a multi column layout reads down one column then the next`() {
        val layout = ColumnLayout.Multi(listOf(300f))
        val lines = LineAssembler().assemble(twoColumnPage(), layout)
        // Every left-column line must precede every right-column line.
        val firstRight = lines.indexOfFirst { it.x > 300f }
        val lastLeft = lines.indexOfLast { it.x < 300f }
        assertTrue(firstRight > lastLeft,
            "columns interleaved: last left at $lastLeft, first right at $firstRight")
        assertTrue(lines.none { it.text.contains("left") && it.text.contains("right") },
            "a line spans the gutter; columns were not split")
    }

    @Test
    fun `assembling the two column fixture keeps columns apart`() {
        PdfBoxTextSource(Fixtures.twoColumnPdf()).use { s ->
            val pages = (0 until s.pageCount()).map { s.page(it) }
            val layout = detector.detect(pages)
            val lines = LineAssembler().assemble(pages[0], layout)
            assertTrue(lines.isNotEmpty())
            val boundary = (layout as ColumnLayout.Multi).boundaries.single()
            val firstRight = lines.indexOfFirst { it.x > boundary }
            val lastLeft = lines.indexOfLast { it.x < boundary }
            if (firstRight >= 0 && lastLeft >= 0) {
                assertTrue(firstRight > lastLeft, "columns interleaved in the fixture")
            }
        }
    }
}
