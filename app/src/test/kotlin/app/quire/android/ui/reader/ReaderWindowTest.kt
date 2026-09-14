package app.quire.android.ui.reader

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.model.ReadingPosition
import app.quire.core.paginate.BlockStyle
import app.quire.core.paginate.Measured
import app.quire.core.paginate.Page
import app.quire.core.paginate.PageWindow
import app.quire.core.paginate.Paginator
import app.quire.core.paginate.TextMeasurer
import app.quire.core.paginate.TypographySettings
import app.quire.core.paginate.Viewport
import app.quire.core.reading.TextAnchor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window the Reader holds over one chapter.
 *
 * Everything here is about what the reader can *see*. A chunk boundary that shows —
 * a half-empty page, a page that changes under a finger, a sentence skipped or shown
 * twice — is the failure this feature must not have, and every test below is one way
 * of catching it.
 */
class ReaderWindowTest {

    private class Measurer : TextMeasurer {
        override fun measure(text: String, style: BlockStyle, widthPx: Float): Measured {
            val perLine = (40 * (19f / style.fontSizeSp)).toInt().coerceAtLeast(1)
            val ends = mutableListOf<Int>()
            var c = 0
            while (c < text.length) { c = (c + perLine).coerceAtMost(text.length); ends += c }
            if (ends.isEmpty()) ends += 0
            return Measured(ends.size * style.lineHeightPx, ends)
        }
    }

    private val settings = TypographySettings(fontSizeSp = 19f, lineHeightMultiple = 1.55f)
    private val viewport = Viewport(1000f, settings.bodyLineHeightPx * 20)
    private val paginator = Paginator(Measurer())

    private val lorem = "Distributed systems are a collection of independent computers. "

    /** One chapter, many blocks: the shape a book with no outline imports as. */
    private val chapter: Chapter = run {
        val each = lorem.repeat(4)
        val blocks = (0 until 900).map { ContentBlock.Paragraph(listOf(InlineSpan(each))) }
        Chapter(0, null, blocks, 0, blocks.size * each.length)
    }

    /** Counts what the Reader asks the paginator for, and what it laid out. */
    private inner class Lay {
        var runs = 0
        var pagesLaidOut = 0
        val lay: suspend (TextAnchor, Int) -> PageWindow = { from, maxPages ->
            runs++
            paginator.paginateWindow(chapter, from, maxPages, viewport, settings)
                .also { pagesLaidOut += it.pages.size }
        }
    }

    private val charsBehind = ReaderWindow.charsBehind(viewport, settings)

    private fun positionAt(offset: Int): ReadingPosition {
        val cursor = chapter.cursorAt(offset)
        return ReadingPosition(0, cursor.blockIndex, cursor.charOffset)
    }

    /** The chapter offset the window's current page opens at. */
    private fun offsetOf(w: WindowedPages): Int {
        val first = w.pages[w.pageIndex].slices.first()
        return chapter.offsetOf(first.blockIndex, first.startChar)
    }

    private fun open(offset: Int, lay: Lay = Lay()) = runBlocking {
        ReaderWindow.openAt(chapter, positionAt(offset), charsBehind, lay.lay)
    }

    // ---------------------------------------------------------------- opening

    @Test
    fun `a window opened deep in a chapter lays out a fraction of it`() {
        val lay = Lay()
        val whole = paginator.paginate(chapter, viewport, settings)
        val w = open(offset = 150_000, lay = lay)
        assertTrue(
            "laid out ${w.pages.size} pages of ${whole.size}",
            w.pages.size < whole.size / 10,
        )
    }

    @Test
    fun `the reader lands on the page holding their own sentence`() {
        val at = 150_000
        val w = open(at)
        val page = w.pages[w.pageIndex]
        val from = chapter.offsetOf(page.slices.first().blockIndex, page.slices.first().startChar)
        val last = page.slices.last()
        val to = chapter.offsetOf(last.blockIndex, last.endChar)
        assertTrue("$at is not on the page [$from, $to)", at in from until to)
    }

    @Test
    fun `a window opened deep in a chapter has room to turn back`() {
        // Without it, the very first backward tap after resuming would have to
        // re-anchor — and re-anchoring is the one thing in this design a reader could
        // conceivably notice.
        val w = open(offset = 150_000)
        // Stated in characters, not pages: how far back a window reaches is decided
        // from an estimate of what a page holds, and the reader is then placed against
        // real page breaks — so the page count depends on how far off the estimate is.
        // What has to hold is the contract: at least `charsBehind` of the chapter is
        // in hand behind the reader, and not wildly more.
        val start = chapter.offsetOf(w.start.blockIndex, w.start.charOffset)
        val behind = offsetOf(w) - start
        assertTrue("only $behind characters behind the reader", behind >= charsBehind)
        assertTrue("$behind characters behind the reader is far too many", behind < charsBehind * 2)
        assertTrue("the reader is on the window's first page", w.pageIndex > 0)
    }

    @Test
    fun `where a window starts does not follow the reader character by character`() {
        // What stops a book walking backwards. Where a window starts decides where its
        // pages break, and the Reader saves the top of the page it was on — so a start
        // taken as "exactly so far before the reader" would be a different start every
        // session, landing the saved place mid-page and saving a slightly earlier one.
        // Snapped to a grid, every place in a band gives the same start, so the tiling
        // is the same and the saved page top is still a page top.
        val at = 150_000
        val here = ReaderWindow.anchorOffset(at, charsBehind)
        assertEquals(
            "the anchor moved for a place a page away",
            here, ReaderWindow.anchorOffset(at + 800, charsBehind),
        )
        assertTrue(
            "the anchor is not at least charsBehind before the reader",
            at - here >= charsBehind,
        )
    }

    @Test
    fun `a window opened at the start of a chapter starts at its first character`() {
        val w = runBlocking { ReaderWindow.openAt(chapter, null, charsBehind, Lay().lay) }
        assertEquals(TextAnchor(0, 0), w.start)
        assertEquals(0, w.pageIndex)
    }

    @Test
    fun `a window opened near the start does not pretend there is room behind it`() {
        val w = open(offset = 300)
        assertEquals(TextAnchor(0, 0), w.start)
        assertTrue("landed on page ${w.pageIndex}", w.pageIndex < ReaderWindow.PAGES_BEHIND)
    }

    @Test
    fun `a window opened at the very end of the chapter reports no next`() {
        val w = open(offset = chapter.textLength)
        assertNull(w.next)
        assertEquals(w.pages.lastIndex, w.pageIndex)
    }

    // ------------------------------------------------------- forward extension

    @Test
    fun `extending forward leaves every page already in hand exactly where it was`() {
        // The property that makes a forward seam invisible: an extension is a pure
        // append followed by a pure drop off the front. The reader keeps looking at
        // the same page, and no page they have seen is laid out a second time.
        val w = open(offset = 150_000)
        val after = runBlocking { ReaderWindow.extendedForward(w, Lay().lay) }

        assertEquals(
            "the reader was shown a different page",
            w.pages[w.pageIndex], after.pages[after.pageIndex],
        )
        val dropped = w.pageIndex - after.pageIndex
        assertTrue("pages were dropped from behind the reader", dropped >= 0)
        assertEquals(
            "a page already in hand was tiled differently",
            w.pages.drop(dropped), after.pages.take(w.pages.size - dropped),
        )
        assertTrue("nothing was added", after.pages.size - after.pageIndex > w.pages.size - w.pageIndex)
    }

    @Test
    fun `extending forward twice is the same as extending once with a bigger budget`() {
        // Chunking must not be able to change where a page break lands. If these ever
        // differ, a seam has become visible.
        val w = open(offset = 150_000)
        val once = runBlocking { ReaderWindow.extendedForward(w, Lay().lay) }
        val twice = runBlocking {
            ReaderWindow.extendedForward(ReaderWindow.extendedForward(w, Lay().lay), Lay().lay)
        }
        once.pages.indices.forEach { index ->
            assertEquals("page $index differs", once.pages[index], twice.pages[index])
        }
    }

    @Test
    fun `a page turned while an extension was running is not undone by it`() {
        // Laying out twelve pages takes tens of milliseconds, and a reader three pages
        // from the edge is reading — so they turn pages while it runs. Appending to the
        // window captured before the lay-out started would write that window's page
        // index back and put them silently on the page they were on when it began: a
        // page turn, lost, with nothing to point at. The lay-out and the append are
        // separate for exactly this reason, and the append takes the window as it is.
        val w = open(offset = 150_000)
        val more = runBlocking { ReaderWindow.extensionFor(w, Lay().lay) }!!

        val turnedTwice = w.copy(pageIndex = w.pageIndex + 2)
        val after = ReaderWindow.appended(turnedTwice, more)

        assertEquals(
            "the reader was moved back to where they were when the run started",
            turnedTwice.pages[turnedTwice.pageIndex], after.pages[after.pageIndex],
        )
    }

    @Test
    fun `a window at the chapter's end cannot be extended and is not laid out again`() {
        val w = open(offset = chapter.textLength)
        val lay = Lay()
        val after = runBlocking { ReaderWindow.extendedForward(w, lay.lay) }
        assertSame(w, after)
        assertEquals("the paginator was run for nothing", 0, lay.runs)
    }

    @Test
    fun `the window asks to be extended before the reader reaches its edge`() {
        // Prefetch, not on-demand. An extension that happened on the turn itself
        // would be a stutter at every seam, which is exactly what a reader would
        // notice — and it is the one thing constraint 1 forbids.
        val w = open(offset = 150_000)
        val edge = w.pages.lastIndex - ReaderWindow.PREFETCH_MARGIN
        assertFalse(ReaderWindow.wantsForwardExtension(w.copy(pageIndex = edge - 1)))
        assertTrue(ReaderWindow.wantsForwardExtension(w.copy(pageIndex = edge)))
    }

    @Test
    fun `a window is never asked to extend past the end of the chapter`() {
        val w = open(offset = chapter.textLength)
        assertFalse(ReaderWindow.wantsForwardExtension(w))
    }

    // -------------------------------------------------------------- trimming

    @Test
    fun `reading forward does not grow the window without bound`() {
        // A window that only grew would end up holding the whole chapter again, which
        // is the problem this exists to solve rather than one it may recreate.
        var w = open(offset = 150_000)
        var guard = 0
        while (w.next != null && guard++ < 40) {
            w = runBlocking {
                ReaderWindow.extendedForward(w.copy(pageIndex = w.pages.lastIndex), Lay().lay)
            }
            assertTrue(
                "the window grew to ${w.pages.size} pages",
                w.pages.size <= ReaderWindow.PAGES_BEHIND + 2 * ReaderWindow.PAGES_AHEAD,
            )
        }
    }

    @Test
    fun `trimming the front moves the page index by exactly what it dropped`() {
        val w = open(offset = 150_000).let { it.copy(pageIndex = it.pages.lastIndex) }
        val shown = w.pages[w.pageIndex]
        val after = runBlocking { ReaderWindow.extendedForward(w, Lay().lay) }
        assertEquals("the reader was shown a different page", shown, after.pages[after.pageIndex])
        assertEquals(
            "the window start does not match its first page",
            after.start,
            after.pages.first().slices.first().let { TextAnchor(it.blockIndex, it.startChar) },
        )
    }

    // ------------------------------------------------------ backward extension

    @Test
    fun `turning back at the window's first page skips nothing`() {
        // The one operation that re-tiles, so this is the one to be exact about. It
        // cannot end the page exactly where the reader was — a tiling laid out from a
        // new start does not have a break there — so it errs on the side that keeps
        // reading continuous: the page runs *to at least* where they were, repeating
        // a little, rather than stopping short and hiding text between the two pages.
        val w = open(offset = 150_000).let { it.copy(pageIndex = 0) }
        val wasAt = offsetOf(w)

        val back = runBlocking { ReaderWindow.turnedBack(chapter, w, charsBehind, Lay().lay) }
        val page = back.pages[back.pageIndex]
        val from = chapter.offsetOf(page.slices.first().blockIndex, page.slices.first().startChar)
        val last = page.slices.last()
        val to = chapter.offsetOf(last.blockIndex, last.endChar)

        assertTrue("the page shown starts at $from, which is not before $wasAt", from < wasAt)
        assertTrue("text was skipped: the page ends at $to, short of $wasAt", to >= wasAt)
        val onePage = paginator.paginate(chapter, viewport, settings)
            .maxOf { p -> p.slices.sumOf { it.length } }
        assertTrue(
            "the overlap was ${to - wasAt} characters, more than a page of $onePage",
            to - wasAt <= onePage,
        )
    }

    @Test
    fun `turning back and forward again reads straight through`() {
        // What the overlap costs, stated as the property that matters: after turning
        // back across a seam and forward again, the reader has seen every character
        // between the two pages and none of them is missing.
        val w = open(offset = 150_000).let { it.copy(pageIndex = 0) }
        val back = runBlocking { ReaderWindow.turnedBack(chapter, w, charsBehind, Lay().lay) }
        val shown = back.pages[back.pageIndex]
        val nextPage = back.pages[back.pageIndex + 1]

        val endOfShown = shown.slices.last()
            .let { chapter.offsetOf(it.blockIndex, it.endChar) }
        val startOfNext = nextPage.slices.first()
            .let { chapter.offsetOf(it.blockIndex, it.startChar) }
        assertEquals("the two pages do not meet", endOfShown, startOfNext)
    }

    @Test
    fun `turning back keeps room to turn back again`() {
        var w = open(offset = 150_000).let { it.copy(pageIndex = 0) }
        repeat(3) {
            w = runBlocking { ReaderWindow.turnedBack(chapter, w, charsBehind, Lay().lay) }
            assertTrue("no room left after a re-anchor", w.pageIndex > 0)
            w = w.copy(pageIndex = 0)
        }
    }

    @Test
    fun `turning back at the first page of the chapter stays put`() {
        val w = runBlocking { ReaderWindow.openAt(chapter, null, charsBehind, Lay().lay) }
        assertTrue(ReaderWindow.atChapterStart(w))
        val lay = Lay()
        assertSame(w, runBlocking { ReaderWindow.turnedBack(chapter, w, charsBehind, lay.lay) })
        assertEquals(0, lay.runs)
    }

    // ------------------------------------------------------------ the edges

    @Test
    fun `the end of the chapter is the end of the window that has no next`() {
        val w = open(offset = 150_000)
        assertFalse(
            "a window with more chapter behind it claimed to be at the end",
            ReaderWindow.atChapterEnd(w.copy(pageIndex = w.pages.lastIndex)),
        )
        val last = open(offset = chapter.textLength)
        assertTrue(ReaderWindow.atChapterEnd(last.copy(pageIndex = last.pages.lastIndex)))
    }

    @Test
    fun `a window is stable across a type-size change made from the same place`() {
        // Stepping the size up and back down must return the reader to the tiling they
        // started from. It does because the window start is sticky: it is the cursor
        // the window already begins at, never one recomputed from where the reader now
        // stands, which would drift a little further back on every tap.
        val w = open(offset = 150_000)
        val again = runBlocking { ReaderWindow.relaidOut(chapter, w, Lay().lay) }
        assertEquals("the window start drifted", w.start, again.start)
        assertEquals("the reader moved", w.pageIndex, again.pageIndex)
        w.pages.indices.forEach { index ->
            assertEquals("page $index was tiled differently", w.pages[index], again.pages[index])
        }
    }

    @Test
    fun `an estimate of a page's characters is within sight of a real one`() {
        // The estimate decides only where a window *starts*, never where a reader is
        // placed — but if it drifted to nonsense the window would have no room behind
        // it, or would lay out ten times what it needs to.
        val pages = paginator.paginate(chapter, viewport, settings)
        val real = pages.dropLast(1).sumOf { page -> page.slices.sumOf { it.length } } /
            (pages.size - 1)
        val estimate = app.quire.core.paginate.Measure.charsPerPage(viewport, settings)
        assertTrue(
            "estimated $estimate characters a page against a real $real",
            estimate in (real / 3)..(real * 3),
        )
    }

    @Test
    fun `a chapter that opens with a page break still opens at its beginning`() {
        // A reflowed PDF routinely starts a chapter with a `PageBreak`, which has no
        // characters. `Chapter.cursorAt(0)` steps over it — correctly, since a cursor
        // inside a block with no characters is ambiguous with the start of the next —
        // so a window anchored through it would begin at `TextAnchor(1, 0)`. Then the
        // page break's slice is dropped, `showsChapterHeaderFor` sees a window that
        // does not start at the beginning and draws no chapter header, and turning back
        // out of the chapter never reaches the previous one.
        val blocks = listOf(ContentBlock.PageBreak(1)) + chapter.blocks.take(40)
        val withBreak = Chapter(0, "One", blocks, 0, 0).let { it.copy(charCount = it.textLength) }
        val w = runBlocking {
            ReaderWindow.openAt(withBreak, null, charsBehind) { from, maxPages ->
                paginator.paginateWindow(withBreak, from, maxPages, viewport, settings)
            }
        }
        assertEquals(TextAnchor(0, 0), w.start)
        assertTrue(ReaderWindow.atChapterStart(w))
        assertEquals(
            "the page break's own slice was dropped",
            0, w.pages.first().slices.first().blockIndex,
        )
        assertTrue(
            "the chapter header would not have been drawn",
            showsChapterHeaderFor(withBreak, pageIndex = 0, windowStart = w.start),
        )
    }

    @Test
    fun `an empty chapter still gives the Reader a page to stand on`() {
        val empty = Chapter(0, null, emptyList(), 0, 0)
        val w = runBlocking {
            ReaderWindow.openAt(empty, null, charsBehind) { from, maxPages ->
                paginator.paginateWindow(empty, from, maxPages, viewport, settings)
            }
        }
        assertEquals(1, w.pages.size)
        assertEquals(0, w.pageIndex)
        assertNotNull(w.start)
    }
}
