package app.folio.core.reflow

import app.folio.core.fixtures.Fixtures
import app.folio.core.fixtures.PdfBoxTextSource
import app.folio.core.model.ContentBlock
import app.folio.core.model.plainText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParagraphAssemblerTest {

    private val assembler = ParagraphAssembler()

    private fun line(
        text: String, y: Float, x: Float = 72f, width: Float = 460f, size: Float = 11f,
        bold: Boolean = false,
    ) = Line(
        text = text, x = x, y = y, width = width, height = size,
        medianFontSize = size, bold = bold, runs = emptyList(),
    )

    /** Full-measure lines at even leading: one continuous paragraph. */
    private fun fullMeasure(vararg texts: String, startY: Float = 700f): List<Line> =
        texts.mapIndexed { i, t -> line(t, startY - i * 16f) }

    @Test
    fun `consecutive full measure lines join into one paragraph`() {
        val blocks = assembler.assemble(
            fullMeasure("Distributed systems are a collection of", "independent computers that appear to")
        )
        assertEquals(1, blocks.size)
        assertEquals(
            "Distributed systems are a collection of independent computers that appear to",
            blocks.single().plainText,
        )
    }

    @Test
    fun `a line ending short of the margin ends the paragraph`() {
        val lines = listOf(
            line("A full measure line running to the margin", 700f, width = 460f),
            line("A short final line.", 684f, width = 120f),
            line("A new paragraph starts here at full measure", 668f, width = 460f),
        )
        val blocks = assembler.assemble(lines)
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].plainText.endsWith("A short final line."))
    }

    @Test
    fun `a first line indent starts a new paragraph`() {
        val lines = listOf(
            line("Body text running the full measure of the page", 700f, x = 72f),
            line("Indented opening of the next paragraph here", 684f, x = 98f),
        )
        assertEquals(2, assembler.assemble(lines).size)
    }

    @Test
    fun `a vertical gap larger than the leading starts a new paragraph`() {
        val lines = listOf(
            line("First block of text at full measure across", 700f),
            line("Second block after a clear vertical gap here", 660f),  // 40pt vs 16pt leading
        )
        assertEquals(2, assembler.assemble(lines).size)
    }

    @Test
    fun `a larger bold isolated line becomes a heading`() {
        val lines = listOf(
            line("The Weight of Silence", 700f, size = 22f, bold = true, width = 220f),
            line("Distributed systems are a collection of things", 650f, size = 11f),
            line("that appear to their users as one coherent whole", 634f, size = 11f),
            line("and behave accordingly under most conditions", 618f, size = 11f),
        )
        val blocks = assembler.assemble(lines)
        val heading = blocks.first()
        assertTrue(heading is ContentBlock.Heading, "expected Heading, got ${heading::class.simpleName}")
        assertEquals("The Weight of Silence", heading.plainText)
    }

    @Test
    fun `body text is never promoted to a heading`() {
        val lines = fullMeasure(
            "Distributed systems are a collection of independent",
            "computers that appear to their users as a single",
            "coherent system and behave that way in practice",
        )
        val blocks = assembler.assemble(lines)
        assertTrue(blocks.none { it is ContentBlock.Heading }, "body text promoted to heading")
    }

    @Test
    fun `a short line at body size is not a heading`() {
        // Uniform type size means no typographic signal, so nothing is a heading.
        val lines = listOf(
            line("Chapter 4", 700f, size = 11f, width = 60f),
            line("Distributed systems are a collection of things", 650f, size = 11f),
        )
        assertTrue(
            assembler.assemble(lines).none { it is ContentBlock.Heading },
            "promoted a same-size line to a heading with no typographic evidence",
        )
    }

    @Test
    fun `empty input yields no blocks`() {
        assertTrue(assembler.assemble(emptyList()).isEmpty())
    }

    @Test
    fun `no content is lost across assembly`() {
        val lines = fullMeasure(
            "One two three four five six seven eight nine ten",
            "eleven twelve thirteen fourteen fifteen sixteen",
            "seventeen eighteen nineteen twenty twentyone",
        )
        val words = assembler.assemble(lines).joinToString(" ") { it.plainText }.split(" ")
        assertEquals(
            lines.joinToString(" ") { it.text }.split(" ").size, words.size,
            "word count changed during paragraph assembly",
        )
    }

    @Test
    fun `assembles the single column fixture into paragraphs without losing words`() {
        PdfBoxTextSource(Fixtures.singleColumnPdf()).use { s ->
            val lines = LineAssembler().assemble(s.page(0))
            val blocks = assembler.assemble(lines)
            assertTrue(blocks.isNotEmpty())
            val before = lines.joinToString(" ") { it.text }.split(Regex("\\s+")).size
            val after = blocks.joinToString(" ") { it.plainText }.split(Regex("\\s+")).size
            assertEquals(before, after, "words lost or duplicated on a real page")
        }
    }
}
