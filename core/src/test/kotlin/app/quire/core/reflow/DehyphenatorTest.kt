package app.quire.core.reflow

import app.quire.core.fixtures.Fixtures
import app.quire.core.fixtures.PdfBoxTextSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DehyphenatorTest {

    private val dehyphenator = Dehyphenator()

    private fun line(text: String, y: Float, width: Float = 460f) = Line(
        text = text, x = 72f, y = y, width = width, height = 11f,
        medianFontSize = 11f, bold = false, runs = emptyList(),
    )

    private fun textsOf(lines: List<Line>) = lines.map { it.text }

    @Test
    fun `joins a word split across two lines`() {
        val out = dehyphenator.join(listOf(
            line("Distributed sys-", 700f), line("tems are everywhere", 684f),
        ))
        assertEquals(listOf("Distributed systems are everywhere"), textsOf(out))
    }

    @Test
    fun `joins a chain of hyphenated line breaks`() {
        val out = dehyphenator.join(listOf(
            line("Distributed sys-", 700f),
            line("tems are a col-", 684f),
            line("lection of computers", 668f),
        ))
        assertEquals(listOf("Distributed systems are a collection of computers"), textsOf(out))
    }

    @Test
    fun `keeps a genuine compound hyphen when the next line starts uppercase`() {
        val out = dehyphenator.join(listOf(
            line("the Anglo-", 700f), line("Saxon period began", 684f),
        ))
        assertEquals(listOf("the Anglo-Saxon period began"), textsOf(out))
        assertTrue(out.single().text.contains("Anglo-Saxon"), "compound hyphen was dropped")
    }

    @Test
    fun `does not join across a paragraph gap`() {
        val out = dehyphenator.join(listOf(
            line("ending in a hyphen-", 700f), line("new paragraph far below", 600f),
        ))
        assertEquals(2, out.size, "joined across a large vertical gap")
    }

    @Test
    fun `leaves an unhyphenated line alone`() {
        val input = listOf(line("no hyphen here", 700f), line("nor here", 684f))
        assertEquals(textsOf(input), textsOf(dehyphenator.join(input)))
    }

    @Test
    fun `does not join a dash used as punctuation`() {
        val out = dehyphenator.join(listOf(
            line("she paused —", 700f), line("then continued speaking", 684f),
        ))
        assertEquals(2, out.size, "an em dash is not a hyphenation")
    }

    @Test
    fun `a trailing hyphen on the last line is preserved`() {
        val out = dehyphenator.join(listOf(line("dangling sys-", 700f)))
        assertEquals(listOf("dangling sys-"), textsOf(out))
    }

    @Test
    fun `the joined line keeps the geometry of the first line`() {
        val out = dehyphenator.join(listOf(
            line("Distributed sys-", 700f), line("tems are here", 684f),
        )).single()
        assertEquals(700f, out.y, 0.01f)
        assertEquals(72f, out.x, 0.01f)
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(dehyphenator.join(emptyList()).isEmpty())
    }

    @Test
    fun `resolves the hyphenation in the header footer fixture`() {
        PdfBoxTextSource(Fixtures.headerFooterPdf()).use { s ->
            val pages = (0 until s.pageCount()).map { s.page(it) }
            val assembler = LineAssembler()
            val lines = pages.map { assembler.assemble(it) }
            val stripped = HeaderFooterDetector()
                .strip(lines, pages.map { it.geometry.height })
            val joined = dehyphenator.join(stripped.pages.first())
            val text = joined.joinToString(" ") { it.text }

            assertTrue(text.contains("Distributed systems are a collection of"),
                "hyphenation unresolved; got: ${text.take(120)}")
            assertTrue(!text.contains("sys- tems"), "hyphen artefact left behind")
        }
    }

    /**
     * A joined line still has to describe a place on the page.
     *
     * Merging summed the two lines widths, which produced a line wider than the
     * paper it was printed on. Nothing checked that, because width is only ever
     * read later — by [ParagraphAssembler], which takes the widest line on a page
     * as the measure. One hyphenated break was therefore enough to make every real
     * line on that page look like it had stopped short of the margin, and a line
     * that stops short ends a paragraph. A whole book came out one line per
     * paragraph, indented on every line.
     */
    @Test
    fun `a joined line is never wider than the page`() {
        val out = dehyphenator.join(listOf(
            line("he picked up the doughnut-", 700f, width = 468f),
            line("shaped bread and ate it", 684f, width = 320f),
        ))
        val joined = out.single()
        assertTrue(
            joined.width <= 612f,
            "a merged line reported ${joined.width}pt on a 612pt page",
        )
    }

    /**
     * Which of the two lines the merged geometry should describe.
     *
     * The head reached the right margin — that is why it was hyphenated at all —
     * so the only question the width is ever asked is whether the *continuation*
     * reached it. Keeping the heads width says every dehyphenated paragraph runs
     * to the margin, and the paragraph that ends on a short continuation is then
     * glued to the one after it.
     */
    @Test
    fun `a joined line ends where its continuation ends`() {
        val out = dehyphenator.join(listOf(
            line("he picked up the doughnut-", 700f, width = 468f),
            line("shaped bread and ate it", 684f, width = 320f),
        ))
        val joined = out.single()
        assertEquals(72f + 320f, joined.right, "the merged line does not end where the page does")
    }

    @Test
    fun `a joined line that runs to the margin keeps the full measure`() {
        val out = dehyphenator.join(listOf(
            line("he picked up the doughnut-", 700f, width = 468f),
            line("shaped bread and smeared it thickly with", 684f, width = 468f),
        ))
        assertEquals(72f + 468f, out.single().right)
    }
}
