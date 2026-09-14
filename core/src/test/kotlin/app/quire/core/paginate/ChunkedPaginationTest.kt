package app.quire.core.paginate

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.reading.TextAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A chunk boundary must be invisible, and this is how that is known.
 *
 * The claim is not "the seam looks fine". It is stronger and it is checkable:
 *
 * > Laying a chapter out as a sequence of chunks, each starting exactly where the
 * > last one stopped, produces **the same pages** that laying the whole chapter out
 * > produces — slice for slice.
 *
 * It holds because a chunk starts at a page boundary, and a fresh lay-out at a page
 * boundary is in the same state whole-chapter pagination is in when it reaches one:
 * `used` is zero, the page is empty, spacing above the first block on a page is zero
 * by definition, and both the paragraph indent and the raised initial are already
 * suppressed for a block resumed part-way through. The only thing reset is the
 * learned characters-per-line, which decides how much text one `measure` call asks
 * for and never where a line breaks.
 *
 * If this test ever goes red, a seam has become visible.
 */
class ChunkedPaginationTest {

    private class FixedMeasurer(private val charsPerLine: Int = 40) : TextMeasurer {
        override fun measure(text: String, style: BlockStyle, widthPx: Float): Measured {
            val perLine = (charsPerLine * (19f / style.fontSizeSp)).toInt().coerceAtLeast(1)
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

    private val settings = TypographySettings(fontSizeSp = 19f, lineHeightMultiple = 1.55f)

    /** Twenty-five lines, which is what a 1080x2400 phone sets at 19sp. */
    private val viewport = Viewport(widthPx = 938f, heightPx = settings.bodyLineHeightPx * 25)

    private val paginator = Paginator(FixedMeasurer())

    private fun prose(chars: Int) = buildString {
        while (length < chars) {
            append(
                "The coffee was terrible, which was the only thing about the morning " +
                    "that felt reliable. She read the sentence again. ",
            )
        }
    }.take(chars)

    private fun chapterOf(blocks: List<ContentBlock>): Chapter {
        val chapter = Chapter(0, "A Chapter", blocks, 0, 0)
        return chapter.copy(charCount = chapter.textLength)
    }

    private fun paragraphs(count: Int, each: Int = 420) =
        chapterOf((0 until count).map { ContentBlock.Paragraph(listOf(InlineSpan(prose(each)))) })

    private fun oneHugeBlock(chars: Int) =
        chapterOf(listOf(ContentBlock.Paragraph(listOf(InlineSpan(prose(chars))))))

    private fun mixed(): Chapter {
        val blocks = mutableListOf<ContentBlock>()
        repeat(14) { section ->
            blocks += ContentBlock.Heading(if (section % 3 == 0) 1 else 2,
                listOf(InlineSpan("Section ${section + 1}")))
            repeat(4) { blocks += ContentBlock.Paragraph(listOf(InlineSpan(prose(380)))) }
            blocks += ContentBlock.BlockQuote(listOf(InlineSpan(prose(160))))
            blocks += ContentBlock.PageBreak(section)
            blocks += ContentBlock.Image("plate$section.png")
        }
        return chapterOf(blocks)
    }

    // ------------------------------------------------------------- the machinery

    /** Every page of the chapter, reached by chunks of [maxPages] at a time. */
    private fun byChunks(chapter: Chapter, maxPages: Int): List<Page> {
        val all = mutableListOf<Page>()
        var from: TextAnchor? = TextAnchor(0, 0)
        var guard = 0
        while (from != null) {
            val window = paginator.paginateWindow(
                chapter, from, maxPages, viewport, settings,
            )
            all += window.pages
            from = window.next
            assertTrue(++guard < 100_000, "chunking did not terminate")
        }
        return all
    }

    private fun whole(chapter: Chapter): List<Page> =
        paginator.paginate(chapter, viewport, settings)

    // ------------------------------------------------------------- the theorem

    @Test
    fun `chunking a chapter of paragraphs produces exactly the pages the chapter does`() {
        val chapter = paragraphs(count = 300)
        val expected = whole(chapter)
        assertTrue(expected.size > 30, "the fixture is too short to have seams")
        listOf(1, 2, 3, 5, 8, 13, 40).forEach { budget ->
            assertEquals(expected, byChunks(chapter, budget), "chunked at $budget pages")
        }
    }

    @Test
    fun `chunking one enormous block produces exactly the pages the chapter does`() {
        // The shape that caused every previous performance bug here: a TXT with no
        // blank lines, a PDF whose reflow merged everything. There is no paragraph
        // boundary to cut at, which is precisely why chunks are cut at page
        // boundaries instead.
        val chapter = oneHugeBlock(120_000)
        val expected = whole(chapter)
        listOf(1, 3, 8, 40).forEach { budget ->
            assertEquals(expected, byChunks(chapter, budget), "chunked at $budget pages")
        }
    }

    @Test
    fun `chunking a chapter of every kind of block produces exactly its pages`() {
        // Headings, quotations set in from the margin, page breaks and images with no
        // caption — the zero-length blocks that still occupy a slot, and the headings
        // orphan control moves between pages.
        val chapter = mixed()
        val expected = whole(chapter)
        assertTrue(expected.size > 10, "the fixture is too short to have seams")
        listOf(1, 2, 4, 7, 40).forEach { budget ->
            assertEquals(expected, byChunks(chapter, budget), "chunked at $budget pages")
        }
    }

    @Test
    fun `a chapter with a header inset chunks to the same pages`() {
        // The inset belongs to the chapter's first page and to no other, so only the
        // first chunk is given it. Budgeting it twice would clip the page a second
        // seam lands on — the failure MeasureMatchesRenderTest exists for, reached
        // through chunking instead of through a style.
        val chapter = paragraphs(count = 120)
        val inset = settings.bodyLineHeightPx * 6
        val expected = paginator.paginate(chapter, viewport, settings, firstPageInsetPx = inset)

        val all = mutableListOf<Page>()
        var from: TextAnchor? = TextAnchor(0, 0)
        while (from != null) {
            val window = paginator.paginateWindow(
                chapter, from, maxPages = 4, viewport = viewport, settings = settings,
                firstPageInsetPx = if (from == TextAnchor(0, 0)) inset else 0f,
            )
            all += window.pages
            from = window.next
        }
        assertEquals(expected, all)
    }

    // ------------------------------------------------------- what a chunk promises

    @Test
    fun `every character appears exactly once across the chunks, in order`() {
        // Conservation, stated over chunks rather than over one lay-out. A paginator
        // that quietly drops a sentence is far worse than one that breaks a page
        // awkwardly, and only the first is silent.
        val chapter = mixed()
        var expectedBlock = 0
        var expectedChar = 0
        byChunks(chapter, maxPages = 3).forEach { page ->
            page.slices.forEach { slice ->
                assertEquals(expectedBlock, slice.blockIndex, "a block was skipped or repeated")
                assertEquals(expectedChar, slice.startChar, "a run of characters went missing")
                expectedChar = slice.endChar
                if (slice.endChar >= chapter.blockTexts[slice.blockIndex].length) {
                    expectedBlock++
                    expectedChar = 0
                }
            }
        }
        assertEquals(chapter.blocks.size, expectedBlock, "the chapter did not finish")
    }

    @Test
    fun `no chunk ends on a short page except the one that ends the chapter`() {
        // The whole reason a chunk is cut at a page boundary rather than at a
        // paragraph. A chunk that ended wherever its text ran out would leave a
        // half-empty page at every seam, which is the single most visible thing a
        // reader could be shown.
        val chapter = paragraphs(count = 200)
        val whole = whole(chapter)
        val fullest = whole.dropLast(1).maxOf { page -> page.slices.sumOf { it.length } }

        var from: TextAnchor? = TextAnchor(0, 0)
        while (from != null) {
            val window = paginator.paginateWindow(chapter, from, 6, viewport, settings)
            val last = window.pages.last()
            if (window.next != null) {
                val filled = last.slices.sumOf { it.length }
                assertTrue(
                    filled > fullest * 0.75,
                    "a chunk ended on a page holding $filled characters of about $fullest",
                )
            }
            from = window.next
        }
    }

    @Test
    fun `a chunk that starts part-way through a paragraph draws it as a continuation`() {
        // A chunk boundary inside a block is a page boundary inside a block, which
        // the Reader already draws with no indent and no raised initial. If it did
        // not, a seam would announce itself as a stray indent mid-sentence.
        val chapter = oneHugeBlock(40_000)
        val second = paginator.paginateWindow(chapter, TextAnchor(0, 0), 3, viewport, settings).next!!
        assertTrue(second.charOffset > 0, "the fixture did not produce a mid-block seam")
        assertTrue(
            !Indentation.shouldIndent(chapter.blocks, second.blockIndex, second.charOffset),
            "a continuation would have been indented",
        )
        assertTrue(
            !ChapterOpening.isChapterOpening(chapter.blocks, second.blockIndex, second.charOffset),
            "a continuation would have taken a raised initial",
        )
    }

    @Test
    fun `a chunk never stops on a page whose last block is a heading`() {
        // Orphan control moves a heading that ended a page onto the next one, and a
        // chunk that could not see the next page could not move it — so a heading
        // would be stranded at the foot of a page at a seam and nowhere else. The
        // chunk runs on by a page instead, which is where that heading was going.
        val chapter = mixed()
        var from: TextAnchor? = TextAnchor(0, 0)
        while (from != null) {
            val window = paginator.paginateWindow(chapter, from, 2, viewport, settings)
            val last = window.pages.last()
            val trailing = last.slices.takeLastWhile {
                chapter.blocks.getOrNull(it.blockIndex) is ContentBlock.Heading
            }
            if (window.next != null && trailing.isNotEmpty()) {
                assertEquals(
                    last.slices.size, trailing.size,
                    "a chunk stopped on a page ending in a heading orphan control would move",
                )
            }
            from = window.next
        }
    }

    // ------------------------------------------------------------- the edges

    @Test
    fun `the chapter's last chunk reports no next`() {
        val chapter = paragraphs(count = 12)
        var from: TextAnchor? = TextAnchor(0, 0)
        var last: PageWindow? = null
        while (from != null) {
            last = paginator.paginateWindow(chapter, from, 4, viewport, settings)
            from = last.next
        }
        assertNull(last!!.next)
    }

    @Test
    fun `a budget larger than the chapter lays the whole chapter out`() {
        val chapter = paragraphs(count = 30)
        val window = paginator.paginateWindow(chapter, TextAnchor(0, 0), 10_000, viewport, settings)
        assertEquals(whole(chapter), window.pages)
        assertNull(window.next)
    }

    @Test
    fun `a window opened at the chapter's end holds one empty page and goes nowhere`() {
        // Entering a chapter backwards asks for its very last character, and a
        // rounding error there must not leave the Reader with no pages at all — an
        // empty page list reports "this is the last page", which the Reader once read
        // as a chapter boundary and abandoned.
        val chapter = paragraphs(count = 3)
        val window = paginator.paginateWindow(
            chapter, TextAnchor(chapter.blocks.size, 0), 4, viewport, settings,
        )
        assertEquals(1, window.pages.size)
        assertNull(window.next)
    }

    @Test
    fun `an empty chapter is one empty page, chunked or not`() {
        val chapter = chapterOf(emptyList())
        val window = paginator.paginateWindow(chapter, TextAnchor(0, 0), 4, viewport, settings)
        assertEquals(whole(chapter), window.pages)
        assertNull(window.next)
    }

    @Test
    fun `a window starts where it was asked to start`() {
        val chapter = paragraphs(count = 40)
        val at = chapter.cursorAt(5_000)
        val window = paginator.paginateWindow(chapter, at, 3, viewport, settings)
        assertEquals(at, window.start)
        val first = window.pages.first().slices.first()
        assertEquals(at.blockIndex, first.blockIndex)
        assertEquals(at.charOffset, first.startChar)
    }
}
