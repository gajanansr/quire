package app.folio.core.paginate

import app.folio.core.model.ContentBlock
import app.folio.core.model.InlineSpan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a paragraph opens with an indent.
 *
 * Books separate paragraphs with an indent; web pages use a blank line. Doing both
 * is the thing that makes a reflowed page read as a screen rather than a book.
 *
 * The four cases that take no indent are the rule, not exceptions to it: in each,
 * the reader already knows a paragraph has begun — from the space above it, or from
 * having just turned the page — and an indent there reads as a mistake.
 */
class IndentationTest {

    private fun para(text: String = "Body text.") =
        ContentBlock.Paragraph(listOf(InlineSpan(text)))

    private fun heading(text: String = "Chapter One") =
        ContentBlock.Heading(1, listOf(InlineSpan(text)))

    @Test
    fun `a paragraph after another paragraph is indented`() {
        val blocks = listOf(para(), para())
        assertTrue(Indentation.shouldIndent(blocks, 1, startChar = 0))
    }

    @Test
    fun `the first paragraph of a chapter is not`() {
        assertFalse(Indentation.shouldIndent(listOf(para()), 0, startChar = 0))
    }

    @Test
    fun `a paragraph following a heading is not`() {
        val blocks = listOf(heading(), para())
        assertFalse(Indentation.shouldIndent(blocks, 1, startChar = 0))
    }

    @Test
    fun `a paragraph following a scene break is not`() {
        val blocks = listOf(para(), ContentBlock.PageBreak(1), para())
        assertFalse(Indentation.shouldIndent(blocks, 2, startChar = 0))
    }

    @Test
    fun `a paragraph continued from the previous page is not`() {
        // The sentence is already in progress. Indenting it would announce a
        // paragraph that did not begin.
        val blocks = listOf(para(), para())
        assertFalse(Indentation.shouldIndent(blocks, 1, startChar = 240))
    }

    @Test
    fun `a heading is never indented`() {
        val blocks = listOf(para(), heading())
        assertFalse(Indentation.shouldIndent(blocks, 1, startChar = 0))
    }

    @Test
    fun `an index out of range is not indented rather than throwing`() {
        assertFalse(Indentation.shouldIndent(listOf(para()), 7, startChar = 0))
        assertFalse(Indentation.shouldIndent(emptyList(), 0, startChar = 0))
    }

    @Test
    fun `paragraphs are separated by the indent and nothing else`() {
        // The other half of the convention. A book does not use both.
        val settings = TypographySettings(fontSizeSp = 19f, pixelsPerSp = 1f)
        assertTrue(
            spacingAbovePx(para(), settings, isFirstOnPage = false) == 0f,
            "a gap was left between paragraphs as well as an indent",
        )
        assertTrue(
            spacingAbovePx(heading(), settings, isFirstOnPage = false) > 0f,
            "a heading still needs air above it",
        )
    }
}
