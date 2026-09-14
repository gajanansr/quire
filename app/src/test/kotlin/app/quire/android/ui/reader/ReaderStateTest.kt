package app.quire.android.ui.reader

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.model.ReadingPosition
import app.quire.core.paginate.BlockStyle
import app.quire.core.paginate.Measured
import app.quire.core.paginate.Page
import app.quire.core.paginate.Paginator
import app.quire.core.paginate.TextMeasurer
import app.quire.core.paginate.Viewport
import app.quire.core.paginate.pageContaining
import app.quire.core.reading.TextAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FixedMeasurer(private val perLineAt19: Int = 40) : TextMeasurer {
    override fun measure(text: String, style: BlockStyle, widthPx: Float): Measured {
        val perLine = (perLineAt19 * (19f / style.fontSizeSp)).toInt().coerceAtLeast(1)
        val ends = mutableListOf<Int>()
        var c = 0
        while (c < text.length) { c = (c + perLine).coerceAtMost(text.length); ends += c }
        if (ends.isEmpty()) ends += 0
        return Measured(ends.size * style.lineHeightPx, ends)
    }
}

class ReaderStateTest {

    private val paginator = Paginator(FixedMeasurer())
    private val viewport = Viewport(1000f, 19f * 1.55f * 20)
    private val lorem = "Distributed systems are a collection of independent computers. "

    private fun chapter(index: Int, text: String, startOffset: Int = 0) = Chapter(
        index = index, title = "Chapter ${index + 1}",
        blocks = listOf(ContentBlock.Paragraph(listOf(InlineSpan(text)))),
        startCharOffset = startOffset, charCount = text.length,
    )

    private fun state(text: String = lorem.repeat(30), chapters: Int = 3): ReaderState {
        val ch = chapter(0, text)
        val prefs = ReaderPreferences()
        return ReaderState(
            loading = false, bookId = "b", bookTitle = "A Book",
            chapterCount = chapters, chapter = ch, chapterIndex = 0,
            chapterTitle = ch.title,
            pages = paginator.paginate(ch, viewport, prefs.toSettings(pixelsPerSp = 1f)),
            preferences = prefs, bookTotalChars = text.length * chapters,
        )
    }

    /**
     * A freshly laid-out page list, with the reader put back on their own sentence.
     *
     * What `ReaderWindow` does for real: the page index is meaningless across a
     * repagination, so it is derived from the character offset every time.
     */
    private fun ReaderState.windowOf(
        newPages: List<Page>,
        at: ReadingPosition = position,
        start: TextAnchor = windowStart,
        next: TextAnchor? = windowNext,
    ) = WindowedPages(start, newPages, next, newPages.pageContaining(at))

    // ------------------------------------------------------------- page turns

    @Test
    fun `turning a page advances the index`() {
        val s = ReaderTransitions.nextPage(state())
        assertEquals(1, s?.pageIndex)
    }

    @Test
    fun `turning past the last page returns null so the caller can change chapter`() {
        val s = state()
        val last = s.copy(pageIndex = s.pages.lastIndex)
        assertNull(ReaderTransitions.nextPage(last))
    }

    @Test
    fun `turning back from the first page returns null`() {
        assertNull(ReaderTransitions.previousPage(state()))
    }

    // ------------------------------------------- page turns made during pagination

    /** A chapter long enough to have somewhere to turn to. */
    private fun longState() = state(text = lorem.repeat(200))

    /** The Reader as it is while a long chapter is still being laid out. */
    private fun stillPaginating() = longState().copy(pages = emptyList(), pageIndex = 0)

    @Test
    fun `a page turn asked for before there were pages is honoured when they arrive`() {
        // Reported from a real phone: on a large book, "initially page change doesn't
        // work at all". With no pages, nextPage reports that this is the last one,
        // which the Reader read as a chapter boundary and abandoned — and the chapter
        // count was not loaded either, so the tap simply vanished.
        val queued = ReaderTransitions.queuedTurn(stillPaginating(), forward = true)
        assertEquals("nothing to turn to yet", 0, queued.pageIndex)

        val settled = ReaderTransitions.repaginated(queued, queued.chapter, queued.windowOf(longState().pages), queued.preferences)
        assertEquals("the tap was thrown away", 1, settled.pageIndex)
        assertEquals("the turn was applied twice", 0, settled.pendingTurns)
    }

    @Test
    fun `three taps during pagination land three pages on`() {
        var s = stillPaginating()
        repeat(3) { s = ReaderTransitions.queuedTurn(s, forward = true) }
        val settled = ReaderTransitions.repaginated(s, s.chapter, s.windowOf(longState().pages), s.preferences)
        assertEquals(3, settled.pageIndex)
    }

    @Test
    fun `taps that cancel out leave the reader where they were`() {
        var s = stillPaginating()
        s = ReaderTransitions.queuedTurn(s, forward = true)
        s = ReaderTransitions.queuedTurn(s, forward = false)
        val settled = ReaderTransitions.repaginated(s, s.chapter, s.windowOf(longState().pages), s.preferences)
        assertEquals(0, settled.pageIndex)
    }

    @Test
    fun `turning back before there are pages does not go past the start`() {
        val queued = ReaderTransitions.queuedTurn(stillPaginating(), forward = false)
        val settled = ReaderTransitions.repaginated(queued, queued.chapter, queued.windowOf(longState().pages), queued.preferences)
        assertEquals(0, settled.pageIndex)
    }

    @Test
    fun `a queued turn does not run past the end of the chapter`() {
        // Clamped inside the chapter on purpose. A tap made while the reader could
        // not see what they were turning is not evidence that they wanted the next
        // chapter, and loading one from a queue would move them somewhere they never
        // chose.
        var s = stillPaginating()
        repeat(500) { s = ReaderTransitions.queuedTurn(s, forward = true) }
        val pages = longState().pages
        val settled = ReaderTransitions.repaginated(s, s.chapter, s.windowOf(pages), s.preferences)
        assertEquals(pages.lastIndex, settled.pageIndex)
        assertEquals(0, settled.pendingTurns)
    }

    @Test
    fun `opening the book applies a turn queued while it was loading`() {
        // The book opening at where it was left — which is the case those taps were
        // made in, since there was nothing else on screen to tap at.
        val queued = ReaderTransitions.queuedTurn(stillPaginating(), forward = true)
        val chapter = queued.chapter!!
        val settled = ReaderTransitions.openedChapter(
            queued, chapter, queued.windowOf(longState().pages, at = ReadingPosition.START),
            at = ReadingPosition.START,
        )
        assertEquals(1, settled.pageIndex)
        assertEquals(0, settled.pendingTurns)
    }

    @Test
    fun `book boundaries are recognised`() {
        val s = state()
        assertTrue(s.atBookStart)
        assertFalse(s.atBookEnd)

        val end = s.copy(chapterIndex = 2, pageIndex = s.pages.lastIndex)
        assertTrue(end.atBookEnd)
    }

    // -------------------------------------------------------------- positions

    @Test
    fun `position comes from the page, never from the page number`() {
        val s = ReaderTransitions.nextPage(state())!!
        val expected = s.pages[1].slices.first()
        assertEquals(expected.startChar, s.position.charOffset)
        assertEquals(expected.blockIndex, s.position.blockIndex)
    }

    @Test
    fun `progress accounts for the chapter's offset in the book`() {
        val text = lorem.repeat(10)
        val second = chapter(1, text, startOffset = text.length)
        val prefs = ReaderPreferences()
        val s = ReaderState(
            loading = false, chapterIndex = 1, chapterCount = 3, chapter = second,
            pages = paginator.paginate(second, viewport, prefs.toSettings(pixelsPerSp = 1f)),
            preferences = prefs, bookTotalChars = text.length * 3,
        )
        // Starting the second of three equal chapters is about a third in.
        assertTrue("progress was ${s.progress}", s.progress in 0.30..0.40)
    }

    @Test
    fun `progress is zero rather than NaN for an empty book`() {
        assertEquals(0.0, ReaderState().progress, 1e-9)
    }

    /** One chapter of many blocks — the shape a book with no outline imports as. */
    private fun manyBlockState(blocks: Int = 400): ReaderState {
        val each = lorem.repeat(3)
        val chapter = Chapter(
            index = 0, title = null,
            blocks = (0 until blocks).map { ContentBlock.Paragraph(listOf(InlineSpan(each))) },
            startCharOffset = 0, charCount = blocks * each.length,
        )
        val prefs = ReaderPreferences()
        return ReaderState(
            loading = false, bookId = "b", chapterCount = 1, chapterIndex = 0,
            chapter = chapter,
            pages = paginator.paginate(chapter, viewport, prefs.toSettings(pixelsPerSp = 1f)),
            preferences = prefs, bookTotalChars = chapter.charCount,
        )
    }

    @Test
    fun `progress reaches the end of a chapter that is the whole book`() {
        // The bug this pins. Progress added `slice.startChar` — the offset inside the
        // reader's *own block* — to the chapter's start offset. On a book of many
        // small chapters that is nearly right, because the chapter offsets carry it.
        // On a book with no outline, which is one chapter of 8,621 blocks, the number
        // never exceeds the length of one paragraph: a reader three hundred screens
        // into The Love Hypothesis still read 0%.
        val s = manyBlockState()
        val last = s.copy(pageIndex = s.pages.lastIndex)
        assertTrue(
            "a reader on the last page of the book read ${"%.1f".format(last.progress * 100)}%",
            last.progress > 0.95,
        )
    }

    @Test
    fun `progress rises page by page within one long chapter`() {
        val s = manyBlockState()
        var previous = -1.0
        s.pages.indices.forEach { page ->
            val at = s.copy(pageIndex = page).progress
            assertTrue("progress went backwards at page $page: $previous -> $at", at > previous)
            previous = at
        }
    }

    // ------------------------------------------------------------ repagination

    @Test
    fun `a typography change keeps the reader in the same place`() {
        val s = ReaderTransitions.nextPage(ReaderTransitions.nextPage(state())!!)!!
        val before = s.position

        val bigger = s.preferences.copy(fontSizeSp = 24f)
        val repaged = paginator.paginate(s.chapter!!, viewport, bigger.toSettings(pixelsPerSp = 1f))
        val after = ReaderTransitions.repaginated(s, s.chapter, s.windowOf(repaged), bigger)

        // The page number changes; the place in the text does not.
        assertTrue("expected more pages at 24sp", after.pages.size > s.pages.size)
        assertTrue(
            "reader was moved: ${before.charOffset} -> ${after.position.charOffset}",
            after.position.charOffset <= before.charOffset,
        )
        assertTrue(after.pageIndex in after.pages.indices)
    }

    @Test
    fun `pages laid out for a chapter the reader has left are discarded`() {
        // Laying out a long chapter takes seconds, and the Contents sheet loads a
        // chapter in a coroutine the repagination effect does not cancel. Accepting
        // chapter 3's pages while the state describes chapter 10 leaves `position` a
        // character offset from one chapter stamped with the other's index — and that
        // is what gets written to the progress row when the Reader closes, so the book
        // reopens somewhere the reader has never been.
        val here = state()
        val left = here.chapter!!
        val arrived = here.copy(
            chapterIndex = 10,
            chapter = chapter(10, lorem.repeat(40)),
            pages = paginator.paginate(
                chapter(10, lorem.repeat(40)), viewport,
                here.preferences.toSettings(pixelsPerSp = 1f),
            ),
        )
        val stalePages = here.pages

        val after = ReaderTransitions.repaginated(arrived, left, arrived.windowOf(stalePages), arrived.preferences)

        assertEquals("the stale pages were accepted", arrived.pages, after.pages)
        assertEquals(10, after.position.chapterIndex)
    }

    @Test
    fun `a turn queued while loading does not follow the reader into a chapter they chose`() {
        // Three taps waiting for the book to appear, then the reader opens Contents
        // and picks a chapter. The taps were for the chapter they were looking at, and
        // landing three pages into a chapter just chosen is not what they asked for.
        var s = stillPaginating()
        repeat(3) { s = ReaderTransitions.queuedTurn(s, forward = true) }
        val chosen = chapter(7, lorem.repeat(200))
        val pages = paginator.paginate(
            chosen, viewport, s.preferences.toSettings(pixelsPerSp = 1f),
        )

        val after = ReaderTransitions.openedChapter(s, chosen, s.windowOf(pages, at = ReadingPosition.START), at = null)

        assertEquals("the queued taps were carried into a chosen chapter", 0, after.pageIndex)
        assertEquals(0, after.pendingTurns)
    }

    @Test
    fun `repagination never leaves the page index out of range`() {
        val s = state()
        val far = s.copy(pageIndex = s.pages.lastIndex)
        val smaller = far.preferences.copy(fontSizeSp = 15f)
        val repaged = paginator.paginate(far.chapter!!, viewport, smaller.toSettings(pixelsPerSp = 1f))
        val after = ReaderTransitions.repaginated(far, far.chapter, far.windowOf(repaged), smaller)
        assertTrue(after.pageIndex in after.pages.indices)
    }

    // ----------------------------------------------------------------- chrome

    @Test
    fun `a centre tap toggles the chrome`() {
        val s = state()
        val shown = ReaderTransitions.tapped(s)
        assertTrue(shown.chromeVisible)
        assertFalse(ReaderTransitions.tapped(shown).chromeVisible)
    }

    @Test
    fun `an open overlay swallows the tap`() {
        // The handoff: any overlay blocks the toggle until it is closed.
        val s = state().copy(overlay = ReaderOverlay.CONTENTS, chromeVisible = true)
        assertEquals(s, ReaderTransitions.tapped(s))
    }

    // ------------------------------------------------------------- typography

    @Test
    fun `the size stepper honours the handoff's bounds`() {
        var prefs = ReaderPreferences(fontSizeSp = 24f)
        var s = state().copy(preferences = prefs)
        assertEquals(24f, ReaderTransitions.fontSizeChanged(s, +1f).fontSizeSp, 0.01f)

        prefs = ReaderPreferences(fontSizeSp = 15f)
        s = state().copy(preferences = prefs)
        assertEquals(15f, ReaderTransitions.fontSizeChanged(s, -1f).fontSizeSp, 0.01f)
    }

    @Test
    fun `the stepper moves within range`() {
        val s = state().copy(preferences = ReaderPreferences(fontSizeSp = 19f))
        assertEquals(20f, ReaderTransitions.fontSizeChanged(s, +1f).fontSizeSp, 0.01f)
        assertEquals(18f, ReaderTransitions.fontSizeChanged(s, -1f).fontSizeSp, 0.01f)
    }

    // -------------------------------------------------------------- chapters

    @Test
    fun `opening a chapter at a position lands on the right page`() {
        val s = state()
        val target = s.pages[2].slices.first()
        val at = ReadingPosition(0, target.blockIndex, target.startChar)
        val opened = ReaderTransitions.openedChapter(
            ReaderState(), s.chapter!!, s.windowOf(s.pages, at = at), at,
        )
        assertEquals(2, opened.pageIndex)
        assertFalse(opened.loading)
    }

    @Test
    fun `opening a chapter with no saved position starts at the beginning`() {
        val s = state()
        val opened = ReaderTransitions.openedChapter(
            ReaderState(), s.chapter!!, s.windowOf(s.pages, at = ReadingPosition.START), null,
        )
        assertEquals(0, opened.pageIndex)
        assertNotNull(opened.chapter)
    }

    // ------------------------------------------------------- bookmark snippets

    /** A page that opens on a running page number, as a real book's pages do. */
    private fun pageOpeningWith(vararg paragraphs: String): ReaderState {
        val ch = Chapter(
            index = 0, title = "Chapter 1",
            blocks = paragraphs.map { ContentBlock.Paragraph(listOf(InlineSpan(it))) },
            startCharOffset = 0, charCount = paragraphs.sumOf { it.length },
        )
        val prefs = ReaderPreferences()
        return ReaderState(
            loading = false, bookId = "b", bookTitle = "A Book", chapter = ch,
            pages = paginator.paginate(ch, viewport, prefs.toSettings(pixelsPerSp = 1f)),
            preferences = prefs,
        )
    }

    @Test
    fun `a bookmark is not named after a page number`() {
        // Seen in the app: a saved bookmark whose whole snippet was "38". The page's
        // first block was the running folio, and a list of places called "38" and
        // "112" tells a reader nothing about what they marked.
        val state = pageOpeningWith("38", "The 5 p.m. December sun lit up the hotel.")
        assertEquals("The 5 p.m. December sun lit up the hotel.", state.currentPageSnippet)
    }

    @Test
    fun `a chapter number heading is skipped the same way`() {
        val state = pageOpeningWith("5", "Bay-gulls, that is how you pronounce them.")
        assertEquals("Bay-gulls, that is how you pronounce them.", state.currentPageSnippet)
    }

    @Test
    fun `a page of nothing but a number still yields it`() {
        // Conservatism: a poor snippet beats an empty one. The reader marked
        // something, and the row has to show whatever was actually there.
        assertEquals("38", pageOpeningWith("38").currentPageSnippet)
    }

    @Test
    fun `a page that opens on prose is unchanged`() {
        val state = pageOpeningWith("The 5 p.m. December sun lit up the hotel.", "Then it set.")
        assertEquals("The 5 p.m. December sun lit up the hotel.", state.currentPageSnippet)
    }

    @Test
    fun `a short line of real words is not mistaken for furniture`() {
        // "'Yeah,' Debu said." is four words and a legitimate paragraph. Only things
        // with no words at all are skipped.
        val state = pageOpeningWith("‘Yeah,’ Debu said.", "And that was that.")
        assertEquals("‘Yeah,’ Debu said.", state.currentPageSnippet)
    }

    @Test
    fun `sharing a page sends the whole page, not its first paragraph`() {
        // Reported from a real phone: "it shares just one line, that is first line,
        // and clips out everything else". Share reused currentPageSnippet, which is
        // deliberately one block — a bookmark row wants one recognisable line. A
        // share wants the passage the reader is actually looking at.
        val state = pageOpeningWith(
            "38",
            "The 5 p.m. December sun lit up the hotel.",
            "Palm trees swayed green in the breeze.",
            "She had not slept.",
        )
        val shared = state.currentPageText

        assertTrue("the first paragraph is missing", shared.contains("December sun"))
        assertTrue("the second paragraph was dropped", shared.contains("Palm trees"))
        assertTrue("the last paragraph was dropped", shared.contains("not slept"))
    }

    @Test
    fun `a shared page reads as paragraphs`() {
        val state = pageOpeningWith("One paragraph.", "And another.")
        assertEquals("One paragraph.\n\nAnd another.", state.currentPageText)
    }

    @Test
    fun `the bookmark snippet is still one line`() {
        // The two must stay different. Putting a whole page in a bookmark row would
        // make the list unreadable, which is why sharing borrowed the wrong one.
        val state = pageOpeningWith("The 5 p.m. sun lit up the hotel.", "Palm trees swayed.")
        assertEquals("The 5 p.m. sun lit up the hotel.", state.currentPageSnippet)
    }

    // -------------------------------------------------------------- chapter header

    /** A chapter shaped the way an EPUB delivers one. */
    private fun chapterOf(title: String?, vararg blocks: ContentBlock) = Chapter(
        index = 2, title = title, blocks = blocks.toList(),
        startCharOffset = 0, charCount = 0,
    )

    private fun headed(title: String?, vararg blocks: ContentBlock) =
        state().copy(chapterIndex = 2, chapterTitle = title, chapter = chapterOf(title, *blocks))

    @Test
    fun `a chapter whose heading repeats its title does not draw the header`() {
        // Reported from a real phone: "part 1 part 1 comes twice". The chapter opens
        // with a page break, so the old rule looked at the break instead of the
        // heading behind it and drew the title above a page that already said it.
        val state = headed(
            "Part 1",
            ContentBlock.PageBreak(sourcePage = 41),
            ContentBlock.Heading(1, listOf(InlineSpan("Part 1"))),
            ContentBlock.Paragraph(listOf(InlineSpan(lorem))),
        )
        assertFalse("Part 1 was drawn above Part 1", state.showsChapterHeader)
    }

    @Test
    fun `a chapter whose heading differs still draws its header`() {
        val state = headed(
            "Part 1",
            ContentBlock.Heading(1, listOf(InlineSpan("The Fall"))),
            ContentBlock.Paragraph(listOf(InlineSpan(lorem))),
        )
        assertTrue("a real chapter title was lost", state.showsChapterHeader)
    }

    @Test
    fun `the header only heads the first page`() {
        val state = headed(
            "Part 1",
            ContentBlock.Heading(1, listOf(InlineSpan("The Fall"))),
            ContentBlock.Paragraph(listOf(InlineSpan(lorem))),
        )
        assertFalse(state.copy(pageIndex = 1).showsChapterHeader)
    }

    @Test
    fun `the label the header draws is the one the check compares against`() {
        // Two expressions for "Chapter 3" is how the check and the drawing drift
        // apart: the chapter says Chapter 3, the header prints Chapter 3, and the
        // comparison is against something else.
        val state = headed(
            null,
            ContentBlock.Heading(1, listOf(InlineSpan("Chapter 3"))),
            ContentBlock.Paragraph(listOf(InlineSpan(lorem))),
        )
        assertEquals("Chapter 3", state.chapterLabel)
        assertFalse("Chapter 3 was drawn above Chapter 3", state.showsChapterHeader)
    }
}
