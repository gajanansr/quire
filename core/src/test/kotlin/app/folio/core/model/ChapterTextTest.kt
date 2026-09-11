package app.folio.core.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A chapter's text is computed once, not on every read.
 *
 * `ContentBlock.plainText` is a computed extension property: every access rebuilds
 * the string with `joinToString`. The Reader reads it per visible block per
 * recomposition, so turning a page rebuilt the whole text of every block on screen —
 * on a 400k-character block, a 400k-character allocation per frame. That is why page
 * turns were slow on long books even though nothing was being repaginated.
 */
class ChapterTextTest {

    private fun para(text: String) = ContentBlock.Paragraph(listOf(InlineSpan(text)))

    private val chapter = Chapter(
        index = 0,
        title = "One",
        blocks = listOf(para("First."), ContentBlock.Heading(1, listOf(InlineSpan("Head"))),
            para("Second."), ContentBlock.PageBreak(1)),
        startCharOffset = 0,
        charCount = 14,
    )

    @Test
    fun `block texts match what plainText would give`() {
        assertEquals(chapter.blocks.map { it.plainText }, chapter.blockTexts)
    }

    @Test
    fun `reading the texts twice does not rebuild them`() {
        // The whole point: the second read is the same object, not an equal one.
        assertSame(chapter.blockTexts, chapter.blockTexts)
    }

    @Test
    fun `a blockless chapter has no texts`() {
        val empty = Chapter(0, null, emptyList(), 0, 0)
        assertTrue(empty.blockTexts.isEmpty())
    }

    @Test
    fun `the cache does not leak into serialization`() {
        // Chapters are stored as JSON on disk. A derived field must not be written
        // there: it would double the file, and a stale copy would outlive an edit.
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(Chapter.serializer(), chapter)
        assertTrue("blockTexts" !in encoded, "derived text was serialized: $encoded")
        val decoded = json.decodeFromString(Chapter.serializer(), encoded)
        assertEquals(chapter.blockTexts, decoded.blockTexts)
    }

    @Test
    fun `equality still depends only on content`() {
        val a = Chapter(0, "One", listOf(para("x")), 0, 1)
        val b = Chapter(0, "One", listOf(para("x")), 0, 1)
        a.blockTexts // force one side's cache
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
