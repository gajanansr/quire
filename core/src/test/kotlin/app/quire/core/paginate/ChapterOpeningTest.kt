package app.quire.core.paginate

import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which letter a chapter raises, and how far down the page it starts.
 *
 * Both are conventions older than the screen. A chapter opens below the top margin,
 * which is what tells a reader something has ended and something else begun; and its
 * first letter is set large.
 */
class ChapterOpeningTest {

    private fun para(text: String = "Palm trees along the Marriott pool.") =
        ContentBlock.Paragraph(listOf(InlineSpan(text)))

    private fun heading(text: String = "2") = ContentBlock.Heading(1, listOf(InlineSpan(text)))

    @Test
    fun `the first paragraph of a chapter opens it`() {
        assertTrue(ChapterOpening.isChapterOpening(listOf(para()), 0, startChar = 0))
    }

    @Test
    fun `a chapter that begins with its own heading raises the prose, not the heading`() {
        // Chapters usually start with a number or a title. The letter to set large
        // is the one that starts the story, not the one that names the chapter.
        val blocks = listOf(heading(), para())
        assertFalse(ChapterOpening.isChapterOpening(blocks, 0, startChar = 0))
        assertTrue(ChapterOpening.isChapterOpening(blocks, 1, startChar = 0))
    }

    @Test
    fun `only the first paragraph opens the chapter`() {
        val blocks = listOf(para(), para(), para())
        assertTrue(ChapterOpening.isChapterOpening(blocks, 0, startChar = 0))
        assertFalse(ChapterOpening.isChapterOpening(blocks, 1, startChar = 0))
        assertFalse(ChapterOpening.isChapterOpening(blocks, 2, startChar = 0))
    }

    @Test
    fun `a paragraph carried over from the previous page does not open anything`() {
        // It is already in progress, and a raised initial mid-sentence is nonsense.
        assertFalse(ChapterOpening.isChapterOpening(listOf(para()), 0, startChar = 300))
    }

    @Test
    fun `an empty paragraph is not an opening`() {
        val blocks = listOf(ContentBlock.Paragraph(listOf(InlineSpan("   "))), para())
        assertFalse(ChapterOpening.isChapterOpening(blocks, 0, startChar = 0))
    }

    @Test
    fun `the sink is a proportion of the page, not a fixed drop`() {
        // The same gap that looks generous on a phone is a rounding error on a
        // tablet, and a fixed one would be both.
        val phone = ChapterOpening.sinkPx(2000f)
        val tablet = ChapterOpening.sinkPx(3000f)
        assertTrue(tablet > phone, "the sink did not grow with the page")
        assertTrue(phone > 0f)
        // And never so deep that it costs the page its text.
        assertTrue(phone < 2000f * 0.25f, "the sink swallowed the page")
    }
}
