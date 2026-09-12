package app.folio.core.reflow

import app.folio.core.model.ContentBlock
import app.folio.core.model.plainText
import app.folio.core.source.PageGeometry
import app.folio.core.source.PdfPage
import app.folio.core.source.TextRun
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A chapter's opening sentence, set large.
 *
 * Books routinely print the first line or two of a chapter in display type. It is
 * still a sentence, and it runs on into body copy. Reading it as a heading split it
 * in half: the first part was printed as a chapter title and the remainder began the
 * paragraph, so the book opened mid-clause — "…the lobby" as a heading, "manager of
 * the Goa Marriott." as the text.
 *
 * The rules that prevent this only ever *refuse* a heading, never create one, which
 * is the safe direction: missing a heading costs styling, inventing one costs the
 * sentence.
 */
class OpeningFlourishTest {

    private fun run(text: String, y: Float, size: Float) = TextRun(
        text = text, x = 72f, y = y, width = text.length * size * 0.5f,
        height = size * 0.58f, fontSize = size, fontName = "Serif",
        bold = false, italic = false,
    )

    /**
     * The lines given, followed by enough body copy to establish what body type is.
     *
     * Without it the median font size of a two-line page is the *large* line, so
     * nothing is ever "larger than body" and every one of these tests passes without
     * exercising anything. Found by the one test that expected a heading.
     */
    private fun blocksOf(vararg runs: TextRun): List<ContentBlock> {
        val filler = (0 until 6).map { i ->
            run("Distributed systems are a collection of independent computers.", 640f - i * 16f, 11f)
        }
        val page = PdfPage(PageGeometry(0, 612f, 792f), runs.toList() + filler)
        return ParagraphAssembler().assemble(LineAssembler().assemble(page))
    }

    @Test
    fun `an opening sentence in display type is not a heading`() {
        val blocks = blocksOf(
            run("'What do you mean, not enough rooms?' I said to", 720f, 19f),
            run("Arijit Banerjee, the lobby manager of the Goa Marriott.", 690f, 11f),
        )
        assertTrue(
            blocks.none { it is ContentBlock.Heading },
            "the opening sentence became a heading: ${blocks.map { it::class.simpleName }}",
        )
    }

    @Test
    fun `the opening sentence stays whole`() {
        val text = blocksOf(
            run("'What do you mean, not enough rooms?' I said to", 720f, 19f),
            run("Arijit Banerjee, the lobby manager of the Goa Marriott.", 690f, 11f),
        ).joinToString(" ") { it.plainText }
        assertTrue("I said to Arijit Banerjee" in text, "the sentence was cut: $text")
    }

    @Test
    fun `a line ending on a function word is never a heading`() {
        val blocks = blocksOf(
            run("The Weight of the", 720f, 22f),
            run("Silence that followed them home.", 690f, 11f),
        )
        assertTrue(blocks.none { it is ContentBlock.Heading }, "ended on 'the'")
    }

    @Test
    fun `a line followed by lower case is never a heading`() {
        val blocks = blocksOf(
            run("A Longer Winter Came", 720f, 22f),
            run("and stayed until the roads were gone.", 690f, 11f),
        )
        assertTrue(blocks.none { it is ContentBlock.Heading }, "next line continued in lower case")
    }

    @Test
    fun `a real chapter title is still a heading`() {
        // The rules must not swallow the thing they are guarding. A title is short,
        // complete, and followed by a new sentence.
        val blocks = blocksOf(
            run("The Weight of Silence", 720f, 22f),
        )
        assertEquals(
            1,
            blocks.count { it is ContentBlock.Heading },
            "a genuine title stopped being a heading: ${blocks.map { it.plainText }}",
        )
        assertEquals("The Weight of Silence", blocks.first().plainText)
    }
}
