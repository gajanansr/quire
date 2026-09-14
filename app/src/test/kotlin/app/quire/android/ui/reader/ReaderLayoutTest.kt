package app.quire.android.ui.reader

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.paginate.BlockStyle
import app.quire.core.paginate.ChapterOpening
import app.quire.core.paginate.Measured
import app.quire.core.paginate.Paginator
import app.quire.core.paginate.TextMeasurer
import app.quire.core.paginate.Viewport
import app.quire.core.reading.TextAnchor
import app.quire.android.ui.theme.QuireTypography
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

    private fun requestFor(
        chapter: Chapter,
        sizeSp: Float = 19f,
        from: TextAnchor = TextAnchor(0, 0),
    ) = ReaderLayout.requestFor(
        chapter = chapter,
        from = from,
        maxPages = ReaderWindow.PAGES_BEHIND + ReaderWindow.PAGES_AHEAD,
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
            pages.windowFor(chapter, requestFor(chapter))
            val afterFirst = measurer.calls
            assertTrue("nothing was laid out at all", afterFirst > 0)

            // The effect runs again — the reader is deeper in, or the box was
            // remeasured and came back the same. Nothing the page breaks depend on
            // changed, so nothing should be measured again.
            pages.windowFor(chapter, requestFor(chapter))
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
            pages.windowFor(chapter, requestFor(chapter, sizeSp = 19f))
            val afterFirst = measurer.calls
            pages.windowFor(chapter, requestFor(chapter, sizeSp = 22f))
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
            pages.windowFor(here, requestFor(here))
            pages.windowFor(elsewhere, requestFor(elsewhere))
            val settled = measurer.calls

            // The reader steps the size. The Reader repaginates the chapter in hand
            // and nothing else — the other chapter's pages are still good at the size
            // they were laid out for, and will be wanted again at that size.
            pages.windowFor(here, requestFor(here, sizeSp = 20f))
            val afterResize = measurer.calls

            pages.windowFor(elsewhere, requestFor(elsewhere))
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
            pages.windowFor(chapter, requestFor(chapter, sizeSp = 19f))
            pages.windowFor(chapter, requestFor(chapter, sizeSp = 20f))
            val settled = measurer.calls
            pages.windowFor(chapter, requestFor(chapter, sizeSp = 19f))
            assertEquals("stepping back re-laid the chapter out", settled, measurer.calls)
        }
    }

    // ------------------------------------------------- when the pages may be grown

    private fun windowedState(
        sizeSp: Float = 19f,
        laidOutAt: Float? = sizeSp,
        view: Viewport = viewport,
    ): ReaderState {
        val chapter = headedChapter()
        return ReaderState(
            chapter = chapter,
            chapterIndex = chapter.index,
            preferences = ReaderPreferences(fontSizeSp = sizeSp),
            windowLayout = laidOutAt?.let {
                LayoutKey(view, ReaderPreferences(fontSizeSp = it).toSettings(density))
            },
        )
    }

    private fun settings(sizeSp: Float) = ReaderPreferences(fontSizeSp = sizeSp).toSettings(density)

    @Test
    fun `pages measured against what is on screen may be grown`() {
        assertTrue(ReaderLayout.mayGrowWindow(windowedState(), viewport, settings(19f)))
    }

    @Test
    fun `pages measured at another type size may not be grown`() {
        // The fault this exists for: extending a window after a type-size change but
        // before the repagination lands appends pages laid out at the new size to
        // pages laid out at the old one. The reader then stands on a page list that is
        // half one measurement and half another — the renderer draws more lines than
        // were budgeted, and their character offset resolves against breaks that do
        // not exist.
        val stale = windowedState(sizeSp = 22f, laidOutAt = 19f)
        assertFalse(ReaderLayout.mayGrowWindow(stale, viewport, settings(22f)))
    }

    @Test
    fun `pages measured before a rotation may not be grown`() {
        val turned = windowedState(view = Viewport(viewport.heightPx, viewport.widthPx))
        assertFalse(ReaderLayout.mayGrowWindow(turned, viewport, settings(19f)))
    }

    @Test
    fun `nothing may be grown before anything has been laid out`() {
        assertFalse(ReaderLayout.mayGrowWindow(windowedState(laidOutAt = null), viewport, settings(19f)))
        assertFalse(ReaderLayout.mayGrowWindow(ReaderState(), viewport, settings(19f)))
    }

    @Test
    fun `a re-anchored window is adopted only if the reader has not moved`() {
        // A backward re-anchor re-tiles the text and chooses which page of the new
        // tiling to stand on, against the window it started from. Two ways that went
        // wrong: a reader who tapped back and then forward was dragged backwards past
        // the page they had just turned to, and two quick taps back launched two runs
        // from the same window — the same answer twice, so two taps moved them one
        // page. Queued now, and applied on top of the run in flight.
        val base = WindowedPages(TextAnchor(4, 0), pages(6), next = null, pageIndex = 0)
        val standing = ReaderState(windowStart = base.start, pageIndex = 0)
        assertTrue(ReaderLayout.mayAdoptReanchor(standing, base))
        assertFalse(
            "a turn made during the re-anchor was written back over",
            ReaderLayout.mayAdoptReanchor(standing.copy(pageIndex = 1), base),
        )
        assertFalse(
            "a window replaced during the re-anchor was written back over",
            ReaderLayout.mayAdoptReanchor(standing.copy(windowStart = TextAnchor(9, 0)), base),
        )
    }

    private fun pages(n: Int) = List(n) { app.quire.core.paginate.Page(
        listOf(app.quire.core.paginate.PageSlice(it, 0, 10)),
    ) }

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

    // ------------------------------------------------------------------ the inset

    private fun inset(
        title: String?,
        view: Viewport = viewport,
        shows: Boolean = true,
    ) = ReaderLayout.headerInsetPx(shows, title, view, density, density)

    /**
     * Exactly what `ReaderScreen.PageContent` draws above the text, in pixels.
     *
     * Written out term by term rather than calling the thing under test, because the
     * whole point is that two independent descriptions of this height agree. If they
     * ever stop agreeing the surplus is clipped off the bottom of the page with no
     * error anywhere.
     */
    private fun drawnHeaderPx(titleLines: Int, view: Viewport = viewport): Float =
        ChapterOpening.sinkPx(view.heightPx) +
            14f * density +
            (if (titleLines > 0) 10f * density + 34f * density * titleLines else 0f) +
            30f * density

    @Test
    fun `the budget matches what the header draws, line for line`() {
        val short = "The Fall"
        assertEquals(
            drawnHeaderPx(ReaderLayout.titleLines(short, viewport.widthPx, density)),
            inset(short),
            0.01f,
        )
    }

    @Test
    fun `a title that wraps is budgeted for every line it wraps to`() {
        // The bug this replaces: the title was budgeted as a multiple of the *body*
        // line height, but it is drawn at a fixed headlineLarge. So the estimate
        // shrank exactly when the reader chose small type — at 15sp it came up eight
        // points short of a two-line title, and the last line of the chapter's first
        // page was sliced off.
        val long = "A Very Long Chapter Title That Will Certainly Not Fit On One Line"
        val lines = ReaderLayout.titleLines(long, viewport.widthPx, density)
        assertTrue("a 65-character title was thought to fit on one line", lines >= 2)
        assertEquals(drawnHeaderPx(lines), inset(long), 0.01f)
    }

    @Test
    fun `the budget does not shrink when the reader chooses smaller type`() {
        // The header is theme type at fixed sizes and two gaps in dp. None of it
        // depends on the reader's body size, and the old estimate pretending it did
        // is precisely how it under-budgeted at 15sp.
        val atFifteen = requestFor(headedChapter(), sizeSp = 15f)
        val atTwentyFour = requestFor(headedChapter(), sizeSp = 24f)
        assertEquals(
            "the header's height moved with the reader's type size",
            atTwentyFour.insetPx, atFifteen.insetPx, 0.01f,
        )
        assertEquals(drawnHeaderPx(2), atFifteen.insetPx, 0.01f)
    }

    @Test
    fun `only the run that opens the chapter is charged for the header`() {
        // A chapter is laid out a window at a time, and only the window that starts at
        // the chapter's first character can produce the page the header is drawn on.
        // Charging the inset at a seam would lay that page out for less than it draws
        // and clip its last line — the failure `MeasureMatchesRenderTest` exists for,
        // reached through chunking instead of through a style. The same cursor decides
        // the budget here and the drawing in `showsChapterHeaderFor`.
        val chapter = headedChapter()
        val midChapter = TextAnchor(1, 240)
        assertTrue("the chapter's own first page went unbudgeted", requestFor(chapter).insetPx > 0f)
        assertEquals(
            "a seam was charged for a header no page in it draws",
            0f, requestFor(chapter, from = midChapter).insetPx, 0.001f,
        )
        assertFalse(
            "the header would have been drawn above a seam",
            showsChapterHeaderFor(chapter, pageIndex = 0, windowStart = midChapter),
        )
    }

    @Test
    fun `an untitled chapter is not budgeted for a title it does not draw`() {
        // The Reader draws the title line only when the chapter has one.
        assertEquals(drawnHeaderPx(titleLines = 0), inset(null), 0.01f)
        assertEquals(drawnHeaderPx(titleLines = 0), inset("   "), 0.01f)
    }

    @Test
    fun `a taller page opens lower`() {
        // The sink is a proportion of the page: the gap that looks generous on a
        // phone is a rounding error on a tablet.
        assertTrue(
            inset("The Fall", Viewport(900f, 3000f)) > inset("The Fall", Viewport(900f, 2000f)),
        )
    }

    @Test
    fun `a chapter drawing no header is budgeted nothing`() {
        assertEquals(0f, inset("The Fall", shows = false), 0f)
    }

    @Test
    fun `a narrow column wraps a title onto more lines than a wide one`() {
        val title = "The Weight of Silence in the Long Afternoon"
        val narrow = ReaderLayout.titleLines(title, 400f, density)
        val wide = ReaderLayout.titleLines(title, 1600f, density)
        assertTrue("a narrower column did not wrap more", narrow >= wide)
    }

    @Test
    fun `the inset's constants are the theme's own`() {
        // The one thing that makes the arithmetic above trustworthy. The paginator
        // budgets the header from numbers written down here; the Reader draws it from
        // the theme. Restyling the header without changing both is how a page comes to
        // be laid out for less than it draws, and the surplus is clipped off the bottom
        // with no error anywhere — so restyling has to fail this instead.
        assertEquals(
            "labelSmall's line height moved; the header budget did not",
            14f, QuireTypography.labelSmall.lineHeight.value, 0.01f,
        )
        assertEquals(
            "headlineLarge's line height moved; the header budget did not",
            ReaderLayout.TITLE_LINE_SP, QuireTypography.headlineLarge.lineHeight.value, 0.01f,
        )
        assertEquals(
            "headlineLarge's type size moved; the title wrap estimate did not",
            ReaderLayout.TITLE_SIZE_SP, QuireTypography.headlineLarge.fontSize.value, 0.01f,
        )
        assertEquals(ReaderLayout.LABEL_LINE_SP, 14f, 0.01f)
    }

    @Test
    fun `a title is never budgeted at fewer than two lines or more than four`() {
        // Rounding up and flooring at two is deliberate: one line too many is
        // whitespace, one line too few is a sentence cut in half at the foot of the
        // page. The ceiling stops one absurd title swallowing the page it opens.
        assertEquals(2, ReaderLayout.titleLines("A", 2000f, density))
        assertEquals(4, ReaderLayout.titleLines("x".repeat(5_000), 900f, density))
        assertEquals(4, ReaderLayout.titleLines("anything", 0.5f, density))
    }
}
