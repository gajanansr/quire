package app.quire.core.txt

import app.quire.core.fixtures.Fixtures
import app.quire.core.model.*
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TxtParserTest {

    private fun tmp(text: String): File =
        File.createTempFile("quire", ".txt").apply { writeText(text); deleteOnExit() }

    @Test
    fun `splits on blank lines and joins wrapped lines within a paragraph`() {
        val book = TxtParser().parse(tmp("First para line one\nline two\n\nSecond para\n"), "id")
        val paras = book.chapters.single().blocks.filterIsInstance<ContentBlock.Paragraph>()
        assertEquals(2, paras.size)
        assertEquals("First para line one line two", paras[0].plainText)
    }

    @Test
    fun `detects chapter headings in the fixture`() {
        val book = TxtParser().parse(Fixtures.plainTxt(), "id")
        assertTrue(book.chapters.size >= 2, "expected 2+ chapters, got ${book.chapters.size}")
    }

    @Test
    fun `char offsets are cumulative and sum to totalChars`() {
        val book = TxtParser().parse(Fixtures.plainTxt(), "id")
        var running = 0
        book.chapters.forEach { c ->
            assertEquals(running, c.startCharOffset, "chapter ${c.index} start offset")
            running += c.charCount
        }
        assertEquals(running, book.totalChars)
    }

    @Test
    fun `empty file fails cleanly rather than throwing`() {
        val book = TxtParser().parse(tmp(""), "id")
        assertEquals(ProcessingStatus.Failed(FailureReason.EMPTY_DOCUMENT), book.status)
    }

    @Test
    fun `text with no chapter markers becomes one chapter, not invented structure`() {
        val book = TxtParser().parse(tmp("Just prose.\n\nMore prose.\n\nAnd more.\n"), "id")
        assertEquals(1, book.chapters.size)
        assertEquals(3, book.chapters.single().blocks.size)
    }

    @Test
    fun `a long line beginning with Chapter is body text, not a heading`() {
        val long = "Chapter 4 was the one she remembered most, because it was the one " +
            "where everything the book had been circling finally arrived."
        val book = TxtParser().parse(tmp(long), "id")
        assertTrue(
            book.chapters.single().blocks.single() is ContentBlock.Paragraph,
            "a 130-char sentence starting with 'Chapter 4' must not become a heading",
        )
    }
}
