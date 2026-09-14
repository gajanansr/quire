package app.quire.android.ui.reader

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.paginate.BlockStyle
import app.quire.core.paginate.Measured
import app.quire.core.paginate.Paginator
import app.quire.core.paginate.TextMeasurer
import app.quire.core.paginate.Viewport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Counts what the Reader actually asks to be laid out. */
private class CountingMeasurer(private val perLineAt19: Int = 40) : TextMeasurer {
    var calls = 0

    override fun measure(text: String, style: BlockStyle, widthPx: Float): Measured {
        calls++
        val perLine = (perLineAt19 * (19f / style.fontSizeSp)).toInt().coerceAtLeast(1)
        val ends = mutableListOf<Int>()
        var c = 0
        while (c < text.length) { c = (c + perLine).coerceAtMost(text.length); ends += c }
        if (ends.isEmpty()) ends += 0
        return Measured(ends.size * style.lineHeightPx, ends)
    }
}

/**
 * When the Reader paginates, and what it paginates against.
 *
 * Reported from a real phone: "large books font resize and initially page change and
 * all doesn't work at all, after some time settles". Three separate faults met there,
 * and each is a property a JVM test can state.
 */
class ReaderLayoutTest {

    private val density = 2.75f
    private val viewport = Viewport(widthPx = 900f, heightPx = 2000f)
    private val prose = "Distributed systems are a collection of independent " +
        "computers that appear to their users as a single coherent system. ".repeat(40)

    private fun heading(text: String) = ContentBlock.Heading(1, listOf(InlineSpan(text)))
    private fun para(text: String) = ContentBlock.Paragraph(listOf(InlineSpan(text)))

    /** A chapter whose own heading differs from its title, so the header is drawn. */
    private fun headedChapter(index: Int = 3) = Chapter(
        index = index, title = "Part 1",
        blocks = listOf(heading("The Fall"), para(prose)),
        startCharOffset = 0, charCount = prose.length,
    )

    /** A chapter that already says its own title, so no header is drawn. */
    private fun selfHeadedChapter(index: Int = 3) = Chapter(
        index = index, title = "Part 1",
        blocks = listOf(heading("Part 1"), para(prose)),
        startCharOffset = 0, charCount = prose.length,
    )

    private fun requestFor(chapter: Chapter, sizeSp: Float = 19f) = ReaderLayout.requestFor(
        chapter = chapter,
        viewport = viewport,
        preferences = ReaderPreferences(fontSizeSp = sizeSp),
        pixelsPerSp = density,
        pixelsPerDp = density,
    )

    // ------------------------------------------------- what the pagination is for

    @Test
    fun `a chapter that draws a header is paginated with room for it`() {
        assertTrue(
            "the renderer draws a header the paginator made no room for",
            requestFor(headedChapter()).insetPx > 0f,
        )
    }

    @Test
    fun `a chapter that draws no header is paginated with no room for one`() {
        // Measurement follows the render both ways. Budgeting a header that is not
        // drawn only wastes whitespace, but the two must still agree or the cache key
        // and the page breaks describe different pages.
        assertEquals(0f, requestFor(selfHeadedChapter()).insetPx, 0.001f)
    }

    @Test
    fun `the budget does not follow the reader down the chapter`() {
        // The fault. Both call sites asked the *open state* for the inset, and
        // `showsChapterHeader` is false on any page but the first — so a type-size
        // change made on page seven laid the chapter out with no room for the header
        // the renderer then drew on page one, and the bottom of that page was clipped
        // off. It also made the cache key depend on where the reader was standing, so
        // no repagination could ever hit it.
        val chapter = headedChapter()
        val standing = ReaderState(chapter = chapter, chapterIndex = chapter.index, pageIndex = 7)

        assertFalse("the question that was asked", standing.showsChapterHeader)
        assertTrue(
            "the question that must be asked",
            showsChapterHeaderFor(chapter, pageIndex = 0),
        )
        assertTrue(
            "pagination budgeted nothing for a header the renderer will draw",
            requestFor(chapter).insetPx > 0f,
        )
    }

    // ------------------------------------------------------ how often it is paid for

    private fun paginatorWith(measurer: CountingMeasurer) =
        ChapterPaginator(Paginator(measurer), PageCache())

    @Test
    fun `opening a chapter lays it out once, not twice`() {
        val measurer = CountingMeasurer()
        val pages = paginatorWith(measurer)
        val chapter = headedChapter()

        runBlocking {
            pages.pagesFor(chapter, requestFor(chapter))
            val afterFirst = measurer.calls
            assertTrue("nothing was laid out at all", afterFirst > 0)

            // The effect runs again — the reader is deeper in, or the box was
            // remeasured and came back the same. Nothing the page breaks depend on
            // changed, so nothing should be measured again.
            pages.pagesFor(chapter, requestFor(chapter))
            assertEquals("the chapter was laid out twice", afterFirst, measurer.calls)
        }
    }

    @Test
    fun `a type-size change does lay the chapter out again`() {
        // The other half: the cache must not be so eager that a real change is missed.
        val measurer = CountingMeasurer()
        val pages = paginatorWith(measurer)
        val chapter = headedChapter()

        runBlocking {
            pages.pagesFor(chapter, requestFor(chapter, sizeSp = 19f))
            val afterFirst = measurer.calls
            pages.pagesFor(chapter, requestFor(chapter, sizeSp = 22f))
            assertTrue("a new type size served the old pages", measurer.calls > afterFirst)
        }
    }

    @Test
    fun `a type-size change does not lay out a chapter the reader is not in`() {
        val measurer = CountingMeasurer()
        val pages = paginatorWith(measurer)
        val here = headedChapter(index = 3)
        val elsewhere = headedChapter(index = 4)

        runBlocking {
            pages.pagesFor(here, requestFor(here))
            pages.pagesFor(elsewhere, requestFor(elsewhere))
            val settled = measurer.calls

            // The reader steps the size. The Reader repaginates the chapter in hand
            // and nothing else — the other chapter's pages are still good at the size
            // they were laid out for, and will be wanted again at that size.
            pages.pagesFor(here, requestFor(here, sizeSp = 20f))
            val afterResize = measurer.calls

            pages.pagesFor(elsewhere, requestFor(elsewhere))
            assertEquals(
                "a chapter the reader is not in was laid out again",
                afterResize, measurer.calls,
            )
            assertTrue("nothing was laid out for the new size", afterResize > settled)
        }
    }

    @Test
    fun `returning to a type size the reader stepped away from costs nothing`() {
        val measurer = CountingMeasurer()
        val pages = paginatorWith(measurer)
        val chapter = headedChapter()

        runBlocking {
            pages.pagesFor(chapter, requestFor(chapter, sizeSp = 19f))
            pages.pagesFor(chapter, requestFor(chapter, sizeSp = 20f))
            val settled = measurer.calls
            pages.pagesFor(chapter, requestFor(chapter, sizeSp = 19f))
            assertEquals("stepping back re-laid the chapter out", settled, measurer.calls)
        }
    }

    // ------------------------------------------------------------- when it may run

    @Test
    fun `nothing is paginated before the box has been measured`() {
        // Pages laid out against a zero-sized viewport are thrown away the moment the
        // box is measured, and paginating a long chapter to throw it away is the
        // whole cost of opening the book, paid for nothing.
        assertEquals(
            PaginationStep.Wait,
            ReaderLayout.stepFor(ReaderState(), Viewport(0f, 0f), typographyLoaded = true),
        )
    }

    @Test
    fun `nothing is paginated before the reader's saved typography has arrived`() {
        // The fault behind "it settles after a while". The viewport is reported as
        // soon as the page is laid out; the saved font and size are a suspending
        // database read. Whichever won, pagination ran — so a long chapter could be
        // laid out at the default 19sp serif and then drawn at the reader's saved
        // 22sp Lora, measured for one size and rendered at another, with nothing to
        // put it right until they touched the type stepper themselves.
        assertEquals(
            PaginationStep.Wait,
            ReaderLayout.stepFor(ReaderState(), viewport, typographyLoaded = false),
        )
    }

    @Test
    fun `once both are known the saved chapter is opened`() {
        assertEquals(
            PaginationStep.Open,
            ReaderLayout.stepFor(ReaderState(), viewport, typographyLoaded = true),
        )
    }

    @Test
    fun `with a chapter in hand a change repaginates it rather than reopening`() {
        // Reopening would go back to the database for the chapter and to the progress
        // row for a position the reader has since moved away from.
        val chapter = headedChapter()
        val open = ReaderState(chapter = chapter, chapterIndex = chapter.index, pageIndex = 4)
        assertEquals(
            PaginationStep.Repaginate(chapter),
            ReaderLayout.stepFor(open, viewport, typographyLoaded = true),
        )
    }

    // ------------------------------------------------------------------ the inset

    @Test
    fun `the inset scales with the page and the type, not with a fixed number`() {
        // The sink is a proportion of the page: the gap that looks generous on a
        // phone is a rounding error on a tablet.
        val small = ReaderLayout.headerInsetPx(true, 2000f, 19f, density, density)
        val tall = ReaderLayout.headerInsetPx(true, 3000f, 19f, density, density)
        val large = ReaderLayout.headerInsetPx(true, 2000f, 24f, density, density)

        assertTrue("a taller page did not open lower", tall > small)
        assertTrue("larger type did not take more room", large > small)
        assertEquals(0f, ReaderLayout.headerInsetPx(false, 2000f, 19f, density, density), 0f)
    }
}
