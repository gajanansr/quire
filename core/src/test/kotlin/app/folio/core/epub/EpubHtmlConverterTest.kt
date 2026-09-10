package app.folio.core.epub

import app.folio.core.model.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpubHtmlConverterTest {

    private val convert = EpubHtmlConverter()::convert

    @Test
    fun `paragraphs and headings map to their block types`() {
        val blocks = convert("<html><body><h2>A Title</h2><p>Some prose.</p></body></html>")
        assertEquals(2, blocks.size)
        val h = blocks[0] as ContentBlock.Heading
        assertEquals(2, h.level)
        assertEquals("A Title", h.plainText)
        assertEquals("Some prose.", (blocks[1] as ContentBlock.Paragraph).plainText)
    }

    @Test
    fun `emphasis and strong become inline styles, not separate blocks`() {
        val blocks = convert("<html><body><p>Plain <em>soft</em> and <strong>hard</strong>.</p></body></html>")
        val spans = (blocks.single() as ContentBlock.Paragraph).spans
        assertEquals("Plain soft and hard.", spans.joinToString("") { it.text })
        assertTrue(spans.any { it.text == "soft" && InlineStyle.EMPHASIS in it.style })
        assertTrue(spans.any { it.text == "hard" && InlineStyle.STRONG in it.style })
    }

    @Test
    fun `nested emphasis inside strong carries both styles`() {
        val blocks = convert("<html><body><p><strong>bold <em>both</em></strong></p></body></html>")
        val spans = (blocks.single() as ContentBlock.Paragraph).spans
        val both = spans.single { it.text == "both" }
        assertTrue(InlineStyle.STRONG in both.style && InlineStyle.EMPHASIS in both.style)
    }

    @Test
    fun `list items become ListItem blocks with ordinals only when ordered`() {
        val ul = convert("<html><body><ul><li>one</li><li>two</li></ul></body></html>")
            .filterIsInstance<ContentBlock.ListItem>()
        assertEquals(2, ul.size)
        assertTrue(ul.all { it.ordinal == null }, "unordered list items must have no ordinal")

        val ol = convert("<html><body><ol><li>first</li><li>second</li></ol></body></html>")
            .filterIsInstance<ContentBlock.ListItem>()
        assertEquals(listOf(1, 2), ol.map { it.ordinal })
    }

    @Test
    fun `blockquote and images are preserved`() {
        val blocks = convert(
            "<html><body><blockquote>Quoted.</blockquote>" +
                "<img src=\"pics/x.jpg\" alt=\"A caption\"/></body></html>"
        )
        assertEquals("Quoted.", blocks.filterIsInstance<ContentBlock.BlockQuote>().single().plainText)
        val img = blocks.filterIsInstance<ContentBlock.Image>().single()
        assertEquals("pics/x.jpg", img.path)
        assertEquals("A caption", img.caption)
    }

    @Test
    fun `script style and nav furniture are dropped`() {
        val blocks = convert(
            "<html><body><script>alert(1)</script><style>p{color:red}</style>" +
                "<nav><a href=\"x\">skip</a></nav><p>Real text.</p></body></html>"
        )
        assertEquals(1, blocks.size)
        assertEquals("Real text.", blocks.single().plainText)
    }

    @Test
    fun `an unknown block element degrades to a paragraph rather than being dropped`() {
        val blocks = convert("<html><body><section-x>Content that must survive.</section-x></body></html>")
        assertEquals("Content that must survive.", blocks.single().plainText)
    }

    @Test
    fun `whitespace is collapsed and empty blocks are skipped`() {
        val blocks = convert("<html><body><p>  spaced\n\n   out  </p><p>   </p></body></html>")
        assertEquals(1, blocks.size)
        assertEquals("spaced out", blocks.single().plainText)
    }

    @Test
    fun `malformed html does not throw`() {
        val blocks = convert("<html><body><p>unclosed <em>tag</body>")
        assertTrue(blocks.isNotEmpty())
    }
}
