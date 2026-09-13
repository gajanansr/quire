package app.quire.core.paginate

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No line of a paragraph is left standing on its own.
 *
 * One line at the foot of a page is an orphan; one carried alone to the head of the
 * next is a widow. Both read as mistakes, and both are fixed the same way — by
 * moving a line rather than breaking there.
 *
 * The invariant that must survive it is conservation: every character appears
 * exactly once, in order. A paginator that tidies a page by losing a line is far
 * worse than one that breaks awkwardly, and only the first is silent.
 */
class StrandedLinesTest {

    /** Each "line" is 40 characters, and each page holds 10 of them. */
    private class FixedMeasurer(private val perLine: Int = 40) : TextMeasurer {
        override fun measure(text: String, style: BlockStyle, widthPx: Float): Measured {
            val ends = mutableListOf<Int>()
            var cursor = 0
            while (cursor < text.length) {
                cursor = (cursor + perLine).coerceAtMost(text.length)
                ends += cursor
            }
            if (ends.isEmpty()) ends += 0
            return Measured(ends.size * style.lineHeightPx, ends)
        }
    }

    private val settings = TypographySettings(fontSizeSp = 10f, lineHeightMultiple = 1f)
    private val viewport = Viewport(widthPx = 400f, heightPx = 100f)   // ten lines
    private val paginator = Paginator(FixedMeasurer())

    private fun prose(chars: Int) = buildString {
        while (length < chars) append("Distributed systems are a collection of xy ")
    }.take(chars)

    private fun chapterOf(vararg sizes: Int): Chapter {
        val blocks = sizes.map { ContentBlock.Paragraph(listOf(InlineSpan(prose(it)))) }
        return Chapter(0, "T", blocks, 0, sizes.sum())
    }

    private fun linesOn(pages: List<Page>, index: Int, chapter: Chapter): Int =
        pages[index].slices.sumOf { slice ->
            val length = slice.length
            if (length == 0) 0 else ((length + 39) / 40)
        }

    @Test
    fun `no page ends with a single line of a paragraph that continues`() {
        // 9 lines of filler, then a long paragraph: one line of it would otherwise
        // fit at the foot of the first page.
        val chapter = chapterOf(40 * 9, 40 * 12)
        val pages = paginator.paginate(chapter, viewport, settings)

        pages.dropLast(1).forEachIndexed { i, page ->
            val last = page.slices.lastOrNull { it.length > 0 } ?: return@forEachIndexed
            val continues = pages[i + 1].slices.any { it.blockIndex == last.blockIndex }
            if (continues) {
                val lines = (last.length + 39) / 40
                assertTrue(lines >= 2, "page $i ends with $lines line of a paragraph")
            }
        }
    }

    @Test
    fun `no page begins with a single trailing line of the paragraph before it`() {
        val chapter = chapterOf(40 * 6, 40 * 15)
        val pages = paginator.paginate(chapter, viewport, settings)

        pages.drop(1).forEachIndexed { i, page ->
            val first = page.slices.firstOrNull { it.length > 0 } ?: return@forEachIndexed
            val continued = pages[i].slices.any { it.blockIndex == first.blockIndex }
            val endsHere = page.slices.none { it.blockIndex == first.blockIndex && it != first }
            if (continued && endsHere) {
                val lines = (first.length + 39) / 40
                assertTrue(lines >= 2, "page ${i + 1} opens with $lines line carried over")
            }
        }
    }

    @Test
    fun `every character still appears exactly once, in order`() {
        // The invariant that outranks every typographic nicety here.
        val chapter = chapterOf(40 * 9, 40 * 12, 40 * 7)
        val pages = paginator.paginate(chapter, viewport, settings)

        chapter.blocks.indices.forEach { blockIndex ->
            val slices = pages.flatMap { it.slices }.filter { it.blockIndex == blockIndex }
            var expected = 0
            slices.forEach { slice ->
                assertEquals(expected, slice.startChar, "block $blockIndex skipped or repeated")
                expected = slice.endChar
            }
            assertEquals(
                chapter.blockTexts[blockIndex].length, expected,
                "block $blockIndex lost its tail",
            )
        }
    }

    @Test
    fun `a paragraph shorter than the minimum is not moved forever`() {
        // A two-line paragraph on its own must still be placed, or pagination would
        // push it from page to page and never terminate.
        val chapter = chapterOf(40 * 2)
        val pages = paginator.paginate(chapter, viewport, settings)
        assertEquals(1, pages.size)
        assertTrue(pages.single().slices.isNotEmpty())
    }
}
