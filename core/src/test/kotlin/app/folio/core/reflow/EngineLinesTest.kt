package app.folio.core.reflow

import app.folio.core.fixtures.Fixtures
import app.folio.core.fixtures.PdfBoxTextSource
import app.folio.core.source.PageGeometry
import app.folio.core.source.PdfPage
import app.folio.core.source.TextRun
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lines come from the PDF engine, not from clustering baselines afterwards.
 *
 * A content stream carries no notion of a line, but a mature engine works one out
 * from the text matrix, its drop threshold and the font's own metrics — and gets
 * superscripts, kerning, inline font changes and column order right in the process.
 * This project used to discard all of that and re-derive lines by grouping runs
 * whose baselines were close, which is a worse method applied to a harder problem.
 *
 * Clustering remains as the fallback for sources with no engine behind them, and
 * these tests pin which path is taken.
 */
class EngineLinesTest {

    private val assembler = LineAssembler()

    private fun run(text: String, x: Float, y: Float, line: Int) = TextRun(
        text = text, x = x, y = y, width = text.length * 5f, height = 6.4f,
        fontSize = 11f, fontName = "Serif", bold = false, italic = false,
        lineIndex = line,
    )

    @Test
    fun `runs the engine put on one line stay on one line`() {
        // Baselines that clustering would split: a superscript sits well above the
        // text it belongs to, and no baseline tolerance separates that case from a
        // genuine line break without also breaking something else.
        val page = PdfPage(
            PageGeometry(0, 612f, 792f),
            listOf(
                run("A footnote follows this word", 72f, 700f, line = 0),
                run("12", 250f, 704f, line = 0),
                run("and the sentence carries on.", 268f, 700f, line = 0),
            ),
        )
        val lines = assembler.assemble(page)
        assertEquals(1, lines.size, "the engine's line was split: ${lines.map { it.text }}")
        assertTrue("footnote follows" in lines.single().text)
        assertTrue("carries on" in lines.single().text)
    }

    @Test
    fun `runs the engine separated stay separate`() {
        // And the converse: baselines close enough to cluster together, which the
        // engine knows are two lines.
        val page = PdfPage(
            PageGeometry(0, 612f, 792f),
            listOf(
                run("The first line of the paragraph", 72f, 700f, line = 0),
                run("The second, set tight beneath it", 72f, 697f, line = 1),
            ),
        )
        val lines = assembler.assemble(page)
        assertEquals(2, lines.size, "two lines were merged: ${lines.map { it.text }}")
    }

    @Test
    fun `a source with no line information still works`() {
        // The fallback is real, not a formality: a page built from synthetic runs
        // has no engine behind it, and must still assemble.
        val page = PdfPage(
            PageGeometry(0, 612f, 792f),
            listOf(
                run("The first line of the paragraph", 72f, 700f, line = -1),
                run("The second line beneath it", 72f, 684f, line = -1),
            ),
        )
        assertEquals(2, assembler.assemble(page).size)
    }

    @Test
    fun `the engine keeps apart what clustering merged`() {
        // Measured, on the fixture that reproduces a real book's chapter opening.
        // Clustering folded the opening line into the third one; the engine does not.
        PdfBoxTextSource(Fixtures.dropCapPdf()).use { source ->
            val page = source.page(0)
            val engine = assembler.assemble(page)
            val clustered = assembler.assemble(
                page.copy(runs = page.runs.map { it.copy(lineIndex = -1) })
            )
            assertTrue(
                engine.size > clustered.size,
                "the engine should keep more lines apart here: " +
                    "${engine.size} vs ${clustered.size}",
            )
            assertTrue(
                engine.any { it.text.trim() == "Palm trees along the Marriott pool swayed green in" },
                "the opening line was not kept whole: ${engine.map { it.text }}",
            )
        }
    }
}
