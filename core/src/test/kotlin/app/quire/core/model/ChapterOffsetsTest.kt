package app.quire.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A chapter has to be able to say how far into itself a position is.
 *
 * Two callers need it and one of them was silently wrong. `ReaderState.progress`
 * added `slice.startChar` — an offset *inside a block* — to the chapter's own start
 * offset, which is nearly right for a book of many small chapters and completely
 * wrong for a book that is one chapter: a reader 300 screens into 8,621 blocks still
 * read 0%. The other caller is the window anchor, which has to find the character
 * *n* before the reader without summing 8,621 block lengths on every page turn.
 */
class ChapterOffsetsTest {

    private fun para(text: String) = ContentBlock.Paragraph(listOf(InlineSpan(text)))

    private fun chapterOf(vararg texts: String) = Chapter(
        index = 0,
        title = null,
        blocks = texts.map { para(it) },
        startCharOffset = 0,
        charCount = texts.sumOf { it.length },
    )

    @Test
    fun `the length of a chapter is the length of its blocks`() {
        assertEquals(12, chapterOf("abcd", "efgh", "ijkl").textLength)
    }

    @Test
    fun `an empty chapter has no text and no first block`() {
        val empty = Chapter(0, null, emptyList(), 0, 0)
        assertEquals(0, empty.textLength)
        assertEquals(0, empty.offsetOf(blockIndex = 0, charOffset = 0))
    }

    @Test
    fun `a block starts where the ones before it end`() {
        val chapter = chapterOf("abcd", "efgh", "ijkl")
        assertEquals(listOf(0, 4, 8), chapter.blockStarts)
        assertEquals(0, chapter.offsetOf(0, 0))
        assertEquals(4, chapter.offsetOf(1, 0))
        assertEquals(10, chapter.offsetOf(2, 2))
    }

    @Test
    fun `an empty block takes no room but still has a place`() {
        // A PageBreak or an uncaptioned image is a zero-length block that still
        // occupies its index — dropping it would shift every later block index, so
        // the offsets have to survive it.
        val chapter = Chapter(
            0, null,
            listOf(para("abcd"), ContentBlock.PageBreak(1), para("efgh")),
            0, 8,
        )
        assertEquals(listOf(0, 4, 4), chapter.blockStarts)
        assertEquals(4, chapter.offsetOf(1, 0))
        assertEquals(4, chapter.offsetOf(2, 0))
        assertEquals(8, chapter.textLength)
    }

    @Test
    fun `an offset past the end of a block is clamped to that block`() {
        // A position saved before the book was reprocessed can point past the end of
        // the block it names. Losing the place is better than a number that reads as
        // a position further into the chapter than the chapter goes.
        val chapter = chapterOf("abcd", "efgh")
        assertEquals(4, chapter.offsetOf(0, 99))
    }

    @Test
    fun `a block index past the end is the end of the chapter`() {
        val chapter = chapterOf("abcd", "efgh")
        assertEquals(8, chapter.offsetOf(5, 0))
    }

    @Test
    fun `every block boundary survives the round trip`() {
        val chapter = chapterOf("alpha", "", "beta", "gamma!", "")
        chapter.blocks.indices.forEach { index ->
            val offset = chapter.offsetOf(index, 0)
            val back = chapter.cursorAt(offset)
            assertEquals(
                offset, chapter.offsetOf(back.blockIndex, back.charOffset),
                "block $index did not round-trip through offset $offset",
            )
        }
    }

    @Test
    fun `every offset in the chapter resolves to a cursor at the same offset`() {
        val chapter = chapterOf("alpha ", "beta gamma ", "delta")
        (0..chapter.textLength).forEach { offset ->
            val cursor = chapter.cursorAt(offset)
            assertEquals(
                offset, chapter.offsetOf(cursor.blockIndex, cursor.charOffset),
                "offset $offset resolved to $cursor",
            )
        }
    }

    @Test
    fun `a cursor is never inside a block that has no characters`() {
        // A zero-length block cannot hold a cursor: an offset that lands on its
        // boundary belongs to the next block that has text, or to the end.
        val chapter = Chapter(
            0, null,
            listOf(para("abcd"), ContentBlock.PageBreak(1), para("efgh")),
            0, 8,
        )
        assertEquals(2, chapter.cursorAt(4).blockIndex)
        assertEquals(0, chapter.cursorAt(4).charOffset)
    }

    @Test
    fun `the offsets are found without walking the chapter`() {
        // The reason this exists rather than `blockTexts.take(i).sumOf { it.length }`:
        // that is O(blocks) and the Reader asks it on every page turn, on a chapter
        // of 8,621 blocks. A prefix sum plus a binary search is O(log blocks).
        val blocks = (0 until 4_000).map { para("x".repeat(100)) }
        val counted = CountingBlocks(blocks)
        val chapter = Chapter(0, null, counted, 0, 400_000)
        chapter.blockStarts
        val afterPrefix = counted.reads
        repeat(100) { chapter.cursorAt(it * 3_000) }
        val perLookup = (counted.reads - afterPrefix) / 100.0
        assertTrue(
            perLookup < 20.0,
            "each lookup read ${"%.0f".format(perLookup)} blocks",
        )
    }

    /** Counts every read of the block list, however it was spelled. */
    private class CountingBlocks(private val backing: List<ContentBlock>) :
        AbstractList<ContentBlock>() {
        var reads = 0L
        override val size: Int get() = backing.size
        override fun get(index: Int): ContentBlock {
            reads++
            return backing[index]
        }
    }
}
