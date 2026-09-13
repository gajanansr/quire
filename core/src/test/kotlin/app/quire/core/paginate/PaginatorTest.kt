package app.quire.core.paginate

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.model.ReadingPosition
import app.quire.core.model.plainText
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A deterministic measurer: a fixed number of characters per line, wrapping at
 * whole characters. Real text metrics are the app's problem; the paginator's
 * problem is arithmetic, and this makes that arithmetic exactly checkable.
 */
private class FixedMeasurer(private val charsPerLineAt19sp: Int = 40) : TextMeasurer {

    override fun measure(text: String, style: BlockStyle, widthPx: Float): Measured {
        // Larger type fits fewer characters per line, as it would on a real page.
        val perLine = (charsPerLineAt19sp * (19f / style.fontSizeSp)).toInt().coerceAtLeast(1)
        val lineEnds = mutableListOf<Int>()
        var cursor = 0
        while (cursor < text.length) {
            cursor = (cursor + perLine).coerceAtMost(text.length)
            lineEnds += cursor
        }
        if (lineEnds.isEmpty()) lineEnds += 0
        return Measured(heightPx = lineEnds.size * style.lineHeightPx, lineEnds = lineEnds)
    }
}

class PaginatorTest {

    private val settings = TypographySettings(fontSizeSp = 19f, lineHeightMultiple = 1.55f)
    private val paginator = Paginator(FixedMeasurer())

    private fun para(text: String) = ContentBlock.Paragraph(listOf(InlineSpan(text)))
    private fun heading(text: String) = ContentBlock.Heading(1, listOf(InlineSpan(text)))

    private fun chapter(vararg blocks: ContentBlock) = Chapter(
        index = 0, title = "One", blocks = blocks.toList(),
        startCharOffset = 0, charCount = blocks.sumOf { it.plainText.length },
    )

    /** Roughly a phone page: 20 body lines. */
    private val viewport = Viewport(widthPx = 1000f, heightPx = 19f * 1.55f * 20)

    private val lorem = ("Distributed systems are a collection of independent computers " +
        "that appear to their users as a single coherent system. ")

    // ------------------------------------------------------------------ basics

    @Test
    fun `a short chapter fits on one page`() {
        val pages = paginator.paginate(chapter(para("A short paragraph.")), viewport, settings)
        assertEquals(1, pages.size)
    }

    @Test
    fun `an empty chapter yields one empty page rather than none`() {
        // Returning no pages would leave the Reader with nothing to render at all.
        val pages = paginator.paginate(
            Chapter(0, null, emptyList(), 0, 0), viewport, settings,
        )
        assertEquals(1, pages.size)
        assertTrue(pages.single().isEmpty)
    }

    @Test
    fun `a long chapter splits across several pages`() {
        val pages = paginator.paginate(chapter(para(lorem.repeat(40))), viewport, settings)
        assertTrue(pages.size > 3, "expected several pages, got ${pages.size}")
    }

    // ------------------------------------------------------------ conservation

    @Test
    fun `no character is lost or duplicated across pages`() {
        val text = lorem.repeat(30)
        val pages = paginator.paginate(chapter(para(text)), viewport, settings)
        assertEquals(text.length, pages.characterCount())
    }

    @Test
    fun `slices reassemble into the original text, in order`() {
        val text = lorem.repeat(25)
        val pages = paginator.paginate(chapter(para(text)), viewport, settings)
        val rebuilt = buildString {
            pages.forEach { page -> page.slices.forEach { append(text, it.startChar, it.endChar) } }
        }
        assertEquals(text, rebuilt)
    }

    @Test
    fun `conservation holds across a mixed chapter`() {
        val blocks = listOf(
            heading("The Weight of Silence"),
            para(lorem.repeat(8)),
            para(lorem.repeat(12)),
            ContentBlock.PageBreak(3),
            para(lorem.repeat(6)),
        )
        val total = blocks.sumOf { it.plainText.length }
        val pages = paginator.paginate(chapter(*blocks.toTypedArray()), viewport, settings)
        assertEquals(total, pages.characterCount())
    }

    @Test
    fun `every block appears somewhere, including zero-length ones`() {
        val blocks = listOf(
            para("first"), ContentBlock.PageBreak(1), ContentBlock.Image("x.jpg"), para("last"),
        )
        val pages = paginator.paginate(chapter(*blocks.toTypedArray()), viewport, settings)
        val seen = pages.flatMap { it.slices }.map { it.blockIndex }.toSet()
        // A dropped zero-length block would shift every later block index and
        // silently invalidate saved positions.
        assertEquals(setOf(0, 1, 2, 3), seen)
    }

    // ------------------------------------------------------------------ splits

    @Test
    fun `splits land on line boundaries, never mid-line`() {
        val text = lorem.repeat(30)
        val pages = paginator.paginate(chapter(para(text)), viewport, settings)
        val perLine = 40
        pages.dropLast(1).forEach { page ->
            val end = page.slices.last().endChar
            assertTrue(end % perLine == 0 || end == text.length, "cut at $end is not a line boundary")
        }
    }

    @Test
    fun `no page exceeds the line budget`() {
        val budget = ceil(viewport.heightPx / (19f * 1.55f)).toInt()
        val pages = paginator.paginate(chapter(para(lorem.repeat(30))), viewport, settings)
        pages.forEach { page ->
            val lines = page.slices.sumOf { ceil(it.length / 40.0).toInt() }
            assertTrue(lines <= budget + 1, "page holds $lines lines, budget is $budget")
        }
    }

    // ----------------------------------------------------------------- headings

    @Test
    fun `a heading is not stranded as the last thing on a page`() {
        // Fill most of a page, then a heading, then a body paragraph.
        val blocks = listOf(para(lorem.repeat(6)), heading("A New Section"), para(lorem.repeat(6)))
        val pages = paginator.paginate(chapter(*blocks.toTypedArray()), viewport, settings)

        pages.dropLast(1).forEachIndexed { i, page ->
            val last = page.slices.lastOrNull() ?: return@forEachIndexed
            val isHeading = blocks.getOrNull(last.blockIndex) is ContentBlock.Heading
            val headingIsWholePage = page.slices.all {
                blocks.getOrNull(it.blockIndex) is ContentBlock.Heading
            }
            assertTrue(!isHeading || headingIsWholePage, "page $i ends on a stranded heading")
        }
    }

    @Test
    fun `orphan control does not lose the heading`() {
        val blocks = listOf(para(lorem.repeat(6)), heading("A New Section"), para(lorem.repeat(6)))
        val total = blocks.sumOf { it.plainText.length }
        val pages = paginator.paginate(chapter(*blocks.toTypedArray()), viewport, settings)
        assertEquals(total, pages.characterCount())
    }

    // ---------------------------------------------------------------- positions

    @Test
    fun `pageContaining finds the page holding a position`() {
        val text = lorem.repeat(30)
        val pages = paginator.paginate(chapter(para(text)), viewport, settings)
        val third = pages[2].slices.first()
        assertEquals(2, pages.pageContaining(ReadingPosition(0, third.blockIndex, third.startChar)))
    }

    @Test
    fun `a position past the end of a block still resolves to a page`() {
        val pages = paginator.paginate(chapter(para(lorem.repeat(10))), viewport, settings)
        val page = pages.pageContaining(ReadingPosition(0, 0, Int.MAX_VALUE))
        assertTrue(page in pages.indices, "expected a real page, got $page")
    }

    @Test
    fun `an unknown position falls back to the first page rather than throwing`() {
        // A position saved before the book was reprocessed may name a block that no
        // longer exists. Losing your place beats crashing on open.
        val pages = paginator.paginate(chapter(para("short")), viewport, settings)
        assertEquals(0, pages.pageContaining(ReadingPosition(0, 99, 500)))
    }

    @Test
    fun `a page reports the position it starts at`() {
        val pages = paginator.paginate(chapter(para(lorem.repeat(20))), viewport, settings)
        val second = pages[1]
        val position = second.startPosition(chapterIndex = 0)
        assertEquals(second.slices.first().startChar, position.charOffset)
        assertEquals(0, position.chapterIndex)
    }

    // --------------------------------------------------------------- typography

    @Test
    fun `larger type produces more pages`() {
        val text = lorem.repeat(30)
        val small = paginator.paginate(chapter(para(text)), viewport, settings.copy(fontSizeSp = 15f))
        val large = paginator.paginate(chapter(para(text)), viewport, settings.copy(fontSizeSp = 24f))
        assertTrue(large.size > small.size, "15sp gave ${small.size}, 24sp gave ${large.size}")
    }

    @Test
    fun `the same character offset survives a typography change`() {
        // The whole reason positions are character offsets rather than page numbers.
        val text = lorem.repeat(30)
        val ch = chapter(para(text))
        val small = paginator.paginate(ch, viewport, settings.copy(fontSizeSp = 15f))
        val large = paginator.paginate(ch, viewport, settings.copy(fontSizeSp = 24f))

        val offset = text.length / 2
        val position = ReadingPosition(0, 0, offset)

        val pageSmall = small.pageContaining(position)
        val pageLarge = large.pageContaining(position)

        assertTrue(pageSmall in small.indices, "lost the position at 15sp")
        assertTrue(pageLarge in large.indices, "lost the position at 24sp")
        // The page number differs; the place in the text does not.
        val sliceSmall = small[pageSmall].slices.first { it.blockIndex == 0 }
        val sliceLarge = large[pageLarge].slices.first { it.blockIndex == 0 }
        assertTrue(offset >= sliceSmall.startChar && offset <= small[pageSmall].slices.last().endChar)
        assertTrue(offset >= sliceLarge.startChar && offset <= large[pageLarge].slices.last().endChar)
    }

    @Test
    fun `conservation still holds at every type size`() {
        val text = lorem.repeat(20)
        listOf(15f, 17f, 19f, 21f, 24f).forEach { size ->
            val pages = paginator.paginate(
                chapter(para(text)), viewport, settings.copy(fontSizeSp = size),
            )
            assertEquals(text.length, pages.characterCount(), "characters lost at ${size}sp")
        }
    }

    // -------------------------------------------------------------- degenerate

    @Test
    fun `a viewport too small for one line still terminates`() {
        // Guards an infinite loop: nothing fits, so nothing would ever advance.
        val tiny = Viewport(widthPx = 1000f, heightPx = 4f)
        val pages = paginator.paginate(chapter(para(lorem)), tiny, settings)
        assertTrue(pages.isNotEmpty())
        assertEquals(lorem.length, pages.characterCount())
    }

    @Test
    fun `a first-page inset reduces what fits on the first page`() {
        // The chapter header shares the text's box; ignoring it clips the last line.
        val text = lorem.repeat(30)
        val none = paginator.paginate(chapter(para(text)), viewport, settings)
        val inset = paginator.paginate(
            chapter(para(text)), viewport, settings,
            firstPageInsetPx = 19f * 1.55f * 8,
        )
        assertTrue(
            inset.first().slices.sumOf { it.length } < none.first().slices.sumOf { it.length },
            "the inset did not reduce the first page",
        )
    }

    @Test
    fun `an inset does not lose characters`() {
        val text = lorem.repeat(20)
        val pages = paginator.paginate(
            chapter(para(text)), viewport, settings, firstPageInsetPx = 19f * 1.55f * 6,
        )
        assertEquals(text.length, pages.characterCount())
    }

    @Test
    fun `an inset larger than the page still terminates`() {
        val text = lorem.repeat(5)
        val pages = paginator.paginate(
            chapter(para(text)), viewport, settings, firstPageInsetPx = viewport.heightPx * 2,
        )
        assertTrue(pages.isNotEmpty())
        assertEquals(text.length, pages.characterCount())
    }

    @Test
    fun `pixel density changes how much fits on a page`() {
        // The viewport is in device pixels and type is in sp. Without the density
        // factor a 2.75x screen fits nearly three times too much and clips.
        val text = lorem.repeat(20)
        val onex = paginator.paginate(
            chapter(para(text)), viewport, settings.copy(pixelsPerSp = 1f),
        )
        val dense = paginator.paginate(
            chapter(para(text)), viewport, settings.copy(pixelsPerSp = 2.75f),
        )
        assertTrue(
            dense.size > onex.size,
            "denser screen should need more pages: ${onex.size} vs ${dense.size}",
        )
        assertEquals(text.length, dense.characterCount())
    }

    @Test
    fun `a zero-sized viewport yields one empty page rather than hanging`() {
        val pages = paginator.paginate(chapter(para(lorem)), Viewport(0f, 0f), settings)
        assertEquals(1, pages.size)
    }
}
