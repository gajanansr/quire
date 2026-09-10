package app.folio.core.reflow

import app.folio.core.fixtures.Fixtures
import app.folio.core.fixtures.PdfBoxTextSource
import app.folio.core.source.PageGeometry
import app.folio.core.source.PdfPage
import app.folio.core.source.TextRun
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineAssemblerTest {

    private val assembler = LineAssembler()

    private fun run(
        text: String, x: Float, y: Float, size: Float = 11f, bold: Boolean = false,
    ) = TextRun(
        text = text, x = x, y = y, width = text.length * size * 0.5f, height = size,
        fontSize = size, fontName = if (bold) "Helvetica-Bold" else "Helvetica",
        bold = bold, italic = false,
    )

    private fun page(vararg runs: TextRun) =
        PdfPage(PageGeometry(0, 612f, 792f), runs.toList())

    @Test
    fun `runs sharing a baseline merge into one line, left to right`() {
        val lines = assembler.assemble(
            page(run("world", 150f, 700f), run("Hello", 72f, 700f))
        )
        assertEquals(1, lines.size)
        assertEquals("Hello world", lines.single().text)
    }

    @Test
    fun `lines are ordered top to bottom`() {
        val lines = assembler.assemble(
            page(run("third", 72f, 660f), run("first", 72f, 700f), run("second", 72f, 680f))
        )
        assertEquals(listOf("first", "second", "third"), lines.map { it.text })
    }

    @Test
    fun `small baseline jitter stays on the same line`() {
        // Kerning and inline font changes shift the reported baseline slightly.
        val lines = assembler.assemble(
            page(run("steady", 72f, 700f), run("jittery", 130f, 701.4f))
        )
        assertEquals(1, lines.size, "a 1.4pt shift at 11pt type is the same line")
    }

    @Test
    fun `a full line gap starts a new line`() {
        val lines = assembler.assemble(
            page(run("upper", 72f, 700f), run("lower", 72f, 684f))
        )
        assertEquals(2, lines.size)
    }

    @Test
    fun `runs at the same baseline in different columns stay one line at this stage`() {
        // Column splitting is ColumnDetector's job and needs whole-document evidence.
        // Assembling here must not pre-empt that decision.
        val lines = assembler.assemble(
            page(run("left column", 60f, 700f), run("right column", 330f, 700f))
        )
        assertEquals(1, lines.size)
    }

    @Test
    fun `line geometry spans its runs and font size is the median`() {
        val lines = assembler.assemble(
            page(run("a", 72f, 700f, size = 10f), run("b", 200f, 700f, size = 30f),
                 run("c", 150f, 700f, size = 12f))
        )
        val line = lines.single()
        assertEquals(72f, line.x, 0.01f)
        assertEquals(12f, line.medianFontSize, 0.01f)
        assertTrue(line.width > 128f, "width ${line.width} should span x=72 to past x=200")
    }

    @Test
    fun `a line is bold only when every run is bold`() {
        val allBold = assembler.assemble(
            page(run("Chapter", 72f, 700f, bold = true), run("One", 140f, 700f, bold = true))
        ).single()
        assertTrue(allBold.bold)

        val mixed = assembler.assemble(
            page(run("Chapter", 72f, 700f, bold = true), run("one", 140f, 700f))
        ).single()
        assertTrue(!mixed.bold, "a partly-bold line is not a bold line")
    }

    @Test
    fun `an empty page yields no lines`() {
        assertTrue(assembler.assemble(page()).isEmpty())
    }

    @Test
    fun `blank runs are discarded`() {
        assertTrue(assembler.assemble(page(run("   ", 72f, 700f))).isEmpty())
    }

    @Test
    fun `assembles the single column fixture into evenly spaced lines`() {
        PdfBoxTextSource(Fixtures.singleColumnPdf()).use { s ->
            val lines = assembler.assemble(s.page(0))
            assertTrue(lines.size >= 5, "expected several lines, got ${lines.size}")
            assertTrue(lines.first().text.startsWith("Distributed"))
            // Descending y, since the origin is bottom-left.
            val ys = lines.map { it.y }
            assertEquals(ys.sortedDescending(), ys, "lines not ordered top to bottom")
        }
    }
}
