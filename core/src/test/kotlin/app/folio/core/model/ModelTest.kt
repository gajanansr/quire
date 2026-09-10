package app.folio.core.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelTest {

    private fun para(s: String) = ContentBlock.Paragraph(listOf(InlineSpan(s)))

    private fun book(vararg chapters: Chapter) = Book(
        id = "b1", title = "T", author = null, coverPath = null,
        metadata = BookMetadata(), sourceFormat = SourceFormat.TXT,
        status = ProcessingStatus.Ready, chapters = chapters.toList(),
    )

    @Test
    fun `plainText concatenates spans in order`() {
        val h = ContentBlock.Heading(
            1,
            listOf(InlineSpan("The "), InlineSpan("Weight", setOf(InlineStyle.STRONG))),
        )
        assertEquals("The Weight", h.plainText)
    }

    @Test
    fun `progress is zero at the start and one at the end`() {
        val b = book(
            Chapter(0, "One", listOf(para("aaaa")), startCharOffset = 0, charCount = 4),
            Chapter(1, "Two", listOf(para("bbbbbb")), startCharOffset = 4, charCount = 6),
        )
        assertEquals(0.0, b.progressAt(ReadingPosition(0, 0, 0)), 1e-9)
        assertEquals(1.0, b.progressAt(ReadingPosition(1, 0, 6)), 1e-9)
        assertEquals(0.4, b.progressAt(ReadingPosition(1, 0, 0)), 1e-9)
    }

    @Test
    fun `progress clamps rather than throwing on an out of range position`() {
        val b = book(Chapter(0, null, listOf(para("aaaa")), 0, 4))
        val p = b.progressAt(ReadingPosition(99, 99, 99))
        assertTrue(p in 0.0..1.0, "progress escaped 0..1: $p")
    }

    @Test
    fun `progress is zero for an empty book rather than dividing by zero`() {
        assertEquals(0.0, book().progressAt(ReadingPosition.START), 1e-9)
    }

    @Test
    fun `pageBreak contributes no characters to progress`() {
        val b = book(Chapter(0, null, listOf(ContentBlock.PageBreak(3), para("abcd")), 0, 4))
        assertEquals(0.0, b.progressAt(ReadingPosition.START), 1e-9)
    }
}
