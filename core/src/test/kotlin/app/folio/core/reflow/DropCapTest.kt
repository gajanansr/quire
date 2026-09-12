package app.folio.core.reflow

import app.folio.core.source.PageGeometry
import app.folio.core.source.PdfPage
import app.folio.core.source.TextRun
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Chapter openings, set the way books set them.
 *
 * A dropped capital is one large letter standing beside the first two or three
 * lines of a paragraph. Its baseline is the *last* of those lines, not the first,
 * so clustering runs by baseline files it with a line it does not belong to. In a
 * real book that produced "The 5 p.m. P" in the middle of a sentence while the word
 * it opened lost its first letter — "alm trees along the Marriott pool".
 *
 * That is not a filter being imprecise. It is the author's words coming out wrong,
 * which is the one failure this pipeline exists to prevent.
 *
 * Written against runs rather than a PDF on purpose. Building a drop cap with a PDF
 * library tests the library's own run-grouping — which merged the cap into the body
 * text and hid the bug entirely — where these are the exact runs a real producer
 * emits, and the thing under test is what this class does with them.
 */
class DropCapTest {

    private fun run(text: String, x: Float, y: Float, size: Float, height: Float) =
        TextRun(
            text = text, x = x, y = y, width = text.length * size * 0.5f,
            height = height, fontSize = size, fontName = "Serif",
            bold = false, italic = false,
        )

    /** Three body lines at 16pt leading, with a three-line capital beside them. */
    private fun openingPage(capSize: Float = 60f, capHeight: Float = 34.7f) = PdfPage(
        geometry = PageGeometry(0, 612f, 792f),
        runs = listOf(
            run("alm trees along the Marriott pool swayed green in", 104f, 648f, 11f, 6.4f),
            run("the breeze. The 5 p.m. December sun lit up the", 104f, 632f, 11f, 6.4f),
            run("hotel's cottages, casting gentle shadows.", 72f, 616f, 11f, 6.4f),
            // Baseline of the *third* line, standing up beside the first.
            run("P", 72f, 616f, capSize, capHeight),
        ),
    )

    private fun linesOf(page: PdfPage) = LineAssembler().assemble(page)

    @Test
    fun `the dropped capital opens the word it belongs to`() {
        val lines = linesOf(openingPage())
        assertEquals(
            "Palm trees along the Marriott pool swayed green in",
            lines.first().text,
            "the capital did not rejoin its word",
        )
    }

    @Test
    fun `the capital does not surface in a later line`() {
        val lines = linesOf(openingPage())
        lines.drop(1).forEach { line ->
            assertTrue(
                !line.text.trim().endsWith(" P") && !line.text.startsWith("P "),
                "a stray capital landed in: ${line.text}",
            )
        }
    }

    @Test
    fun `every line survives and keeps its order`() {
        val lines = linesOf(openingPage())
        assertEquals(3, lines.size, "lines were merged or lost: ${lines.map { it.text }}")
        assertEquals(listOf(648f, 632f, 616f), lines.map { it.y })
        assertTrue(lines[1].text.startsWith("the breeze."))
        assertTrue(lines[2].text.startsWith("hotel's cottages"))
    }

    @Test
    fun `the opened line keeps body type, not the capital's`() {
        // The drop cap's size belongs to the printed page, not to the sentence.
        // Carrying it through would set one letter of the paragraph five times too
        // large, and would make the line look like a heading to everything after.
        val lines = linesOf(openingPage())
        assertEquals(11f, lines.first().medianFontSize)
    }

    @Test
    fun `a large letter that stands beside only one line is left alone`() {
        // The distinguishing evidence is height, not size. A single large glyph that
        // does not reach past its own line is an initial, a list marker or a stray —
        // and folding it into a neighbour would be inventing a word.
        val page = openingPage(capSize = 18f, capHeight = 10f)
        val lines = linesOf(page)
        assertTrue(
            lines.none { it.text.startsWith("Palm") },
            "a short capital was treated as a drop cap: ${lines.map { it.text }}",
        )
        assertTrue(
            lines.any { it.text.trim() == "P" } || lines.any { it.text.contains("P") },
            "the letter was lost entirely: ${lines.map { it.text }}",
        )
    }

    @Test
    fun `a page with no capital is unchanged`() {
        val plain = PdfPage(
            geometry = PageGeometry(0, 612f, 792f),
            runs = listOf(
                run("Palm trees along the Marriott pool swayed green in", 72f, 648f, 11f, 6.4f),
                run("the breeze. The 5 p.m. December sun lit up the", 72f, 632f, 11f, 6.4f),
            ),
        )
        val lines = linesOf(plain)
        assertEquals(2, lines.size)
        assertEquals("Palm trees along the Marriott pool swayed green in", lines.first().text)
    }

    @Test
    fun `an engine that already grouped the capital is not second-guessed`() {
        // Taken from a real book: PDFBox puts the 65pt "P" and the 20pt line it
        // opens on the same line, and the fix is to leave that alone. Holding the
        // capital back to place it geometrically took it out of a correct grouping
        // and put it back worse — the same mistake as re-deriving lines at all.
        val page = PdfPage(
            geometry = PageGeometry(0, 612f, 792f),
            runs = listOf(
                TextRun("P", 77f, 627f, 33f, 30f, 65f, "Serif", false, false, lineIndex = 2),
                TextRun(
                    "alm trees along the Marriott pool swayed green in the breeze.",
                    107f, 639f, 430f, 9.2f, 20f, "Serif", false, false, lineIndex = 2,
                ),
                TextRun(
                    "December sun lit up the hotel's cottages, casting shadows.",
                    107f, 623f, 420f, 9.2f, 20f, "Serif", false, false, lineIndex = 3,
                ),
            ),
        )
        val lines = LineAssembler().assemble(page)
        assertEquals(2, lines.size, "the engine's grouping was broken up")
        assertTrue(
            lines.first().text.startsWith("Palm trees along the Marriott"),
            "the capital did not join its word: ${lines.first().text}",
        )
    }

    @Test
    fun `one huge letter does not make its line a heading`() {
        // A 65pt capital on a line of 20pt prose. Taking the plain median of two
        // runs gives 65, and everything downstream then reads the sentence as a
        // chapter title and prints it as one.
        val page = PdfPage(
            geometry = PageGeometry(0, 612f, 792f),
            runs = listOf(
                TextRun("P", 77f, 627f, 33f, 30f, 65f, "Serif", false, false, lineIndex = 2),
                TextRun(
                    "alm trees along the Marriott pool swayed green in the breeze.",
                    107f, 639f, 430f, 9.2f, 20f, "Serif", false, false, lineIndex = 2,
                ),
            ),
        )
        val line = LineAssembler().assemble(page).single()
        assertEquals(20f, line.medianFontSize, "the capital defined the line's type size")
        assertEquals(639f, line.y, "the line sits on the capital's baseline, not its own")
    }
}