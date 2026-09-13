package app.quire.core.epub

import app.quire.core.fixtures.Fixtures
import app.quire.core.model.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpubParserTest {

    @Test
    fun `parses title author and metadata from a clean epub`() {
        val book = EpubParser().parse(Fixtures.cleanEpub(), "id")
        assertEquals("A History of Quiet Things", book.title)
        assertEquals("Ada Marlowe", book.author)
        assertEquals("en", book.metadata.language)
        assertEquals(SourceFormat.EPUB, book.sourceFormat)
        assertEquals(ProcessingStatus.Ready, book.status)
    }

    @Test
    fun `one chapter per spine document, in reading order`() {
        val book = EpubParser().parse(Fixtures.cleanEpub(), "id")
        assertEquals(2, book.chapters.size)
        assertEquals(listOf(0, 1), book.chapters.map { it.index })
    }

    @Test
    fun `chapter titles come from the nav document when present`() {
        val book = EpubParser().parse(Fixtures.cleanEpub(), "id")
        assertEquals("The Weight of Silence", book.chapters[0].title)
        assertEquals("What the River Kept", book.chapters[1].title)
    }

    @Test
    fun `chapter titles fall back to the first heading when there is no nav`() {
        val book = EpubParser().parse(Fixtures.epubNoNav(), "id")
        assertEquals(2, book.chapters.size)
        assertEquals("The Weight of Silence", book.chapters[0].title)
    }

    @Test
    fun `structure inside a chapter is preserved`() {
        val book = EpubParser().parse(Fixtures.cleanEpub(), "id")
        val blocks = book.chapters[0].blocks
        assertTrue(blocks.any { it is ContentBlock.Heading }, "heading missing")
        assertTrue(blocks.count { it is ContentBlock.Paragraph } >= 2, "paragraphs missing")
        assertTrue(blocks.count { it is ContentBlock.ListItem } == 2, "list items missing")
        assertTrue(blocks.any { it is ContentBlock.BlockQuote }, "blockquote missing")
    }

    @Test
    fun `char offsets are cumulative and sum to totalChars`() {
        val book = EpubParser().parse(Fixtures.cleanEpub(), "id")
        var running = 0
        book.chapters.forEach { c ->
            assertEquals(running, c.startCharOffset, "chapter ${c.index} start offset")
            running += c.charCount
        }
        assertEquals(running, book.totalChars)
        assertTrue(book.totalChars > 0)
    }

    @Test
    fun `malformed epub fails cleanly with a corrupt file reason`() {
        val book = EpubParser().parse(Fixtures.malformedEpub(), "id")
        assertEquals(ProcessingStatus.Failed(FailureReason.CORRUPT_FILE), book.status)
        assertTrue(book.chapters.isEmpty())
    }

    @Test
    fun `a png masquerading as an epub fails cleanly rather than throwing`() {
        val book = EpubParser().parse(Fixtures.unsupportedFile(), "id")
        assertTrue(book.status is ProcessingStatus.Failed, "expected Failed, got ${book.status}")
    }
}
