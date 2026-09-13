package app.folio.android.ui.reader

import app.folio.core.model.Chapter
import app.folio.core.model.ContentBlock
import app.folio.core.model.InlineSpan
import app.folio.core.model.ReadingPosition
import app.folio.core.paginate.BlockStyle
import app.folio.core.paginate.Measured
import app.folio.core.paginate.Paginator
import app.folio.core.paginate.TextMeasurer
import app.folio.core.paginate.Viewport
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

    // ------------------------------------------------------------ repagination

    @Test
    fun `a typography change keeps the reader in the same place`() {
        val s = ReaderTransitions.nextPage(ReaderTransitions.nextPage(state())!!)!!
        val before = s.position

        val bigger = s.preferences.copy(fontSizeSp = 24f)
        val repaged = paginator.paginate(s.chapter!!, viewport, bigger.toSettings(pixelsPerSp = 1f))
        val after = ReaderTransitions.repaginated(s, repaged, bigger)

        // The page number changes; the place in the text does not.
        assertTrue("expected more pages at 24sp", after.pages.size > s.pages.size)
        assertTrue(
            "reader was moved: ${before.charOffset} -> ${after.position.charOffset}",
            after.position.charOffset <= before.charOffset,
        )
        assertTrue(after.pageIndex in after.pages.indices)
    }

    @Test
    fun `repagination never leaves the page index out of range`() {
        val s = state()
        val far = s.copy(pageIndex = s.pages.lastIndex)
        val smaller = far.preferences.copy(fontSizeSp = 15f)
        val repaged = paginator.paginate(far.chapter!!, viewport, smaller.toSettings(pixelsPerSp = 1f))
        val after = ReaderTransitions.repaginated(far, repaged, smaller)
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
        val opened = ReaderTransitions.openedChapter(
            ReaderState(), s.chapter!!, s.pages,
            ReadingPosition(0, target.blockIndex, target.startChar),
        )
        assertEquals(2, opened.pageIndex)
        assertFalse(opened.loading)
    }

    @Test
    fun `opening a chapter with no saved position starts at the beginning`() {
        val s = state()
        val opened = ReaderTransitions.openedChapter(ReaderState(), s.chapter!!, s.pages, null)
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
}
