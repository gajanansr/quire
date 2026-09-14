package app.quire.android.ui.reader

import app.quire.core.model.Chapter
import app.quire.core.model.ReadingPosition
import app.quire.core.paginate.Measure
import app.quire.core.paginate.Page
import app.quire.core.paginate.PageWindow
import app.quire.core.paginate.TypographySettings
import app.quire.core.paginate.Viewport
import app.quire.core.paginate.pageContaining
import app.quire.core.reading.TextAnchor

/**
 * The pages the Reader is holding, and where in them the reader is standing.
 *
 * [start] is the cursor the window was laid out from, and it is **sticky**: a
 * type-size change lays the window out again from the same cursor rather than from
 * one recomputed around wherever the reader now stands. Recomputing would move the
 * start a little further back on every tap of the stepper, and each move re-tiles the
 * pages — the reader would see the page under them shift while doing nothing but
 * changing the type size.
 */
data class WindowedPages(
    val start: TextAnchor,
    val pages: List<Page>,
    /** Where the chapter continues past this window, or null when it ends in it. */
    val next: TextAnchor?,
    val pageIndex: Int,
)

/**
 * How much of a chapter is laid out at once, and when more of it is.
 *
 * A book with no outline is one chapter. *The Love Hypothesis* imports as 8,621
 * blocks and 565,896 characters of it, and laying all of that out — on open, and
 * again on every tap of A+ — is what made the book hang. This holds a window over the
 * same chapter instead: the reader's place, pages either side of it, and more laid out
 * as they read.
 *
 * **The reader must never be able to tell.** Three of the four operations here are
 * exact, in the sense that the pages they produce are the pages whole-chapter
 * pagination produces (`ChunkedPaginationTest` proves that of the paginator, and
 * these only ever ask it to continue from a boundary it produced):
 *
 * - [openAt] lays out a window around a place.
 * - [extendedForward] appends. No page already in hand is laid out again and the
 *   reader's page does not move.
 * - [relaidOut] re-runs the same window after a typography change, from the same
 *   sticky start.
 *
 * The fourth, [turnedBack], is the one that re-tiles, and it cannot not — pages must
 * tile the text, so pages *before* a place have to end exactly at it, and pagination
 * only runs forwards. What it guarantees instead is that the reader is shown the page
 * whose text ends exactly where the page they were on began: nothing skipped, nothing
 * repeated, and no short page. It is also the only operation that never runs
 * speculatively, because moving a page under a reader who did not ask is the one
 * thing a prefetch must not do.
 */
object ReaderWindow {

    /**
     * Pages laid out ahead of the reader in one run.
     *
     * Twelve. At the measured 422–1,039 characters a page (24sp to 15sp on a
     * 1080×2400 phone) that is 5,064–12,468 characters — under a fortieth of this
     * book's chapter — and about ten minutes of reading at 220 words a minute, so a
     * forward extension is rare as well as cheap.
     */
    const val PAGES_AHEAD = 12

    /**
     * Pages kept behind the reader before the window is trimmed.
     *
     * Eight. Far enough back to pick up a lost thread, which is what a reader turning
     * backwards is doing; past it, [turnedBack] applies. Held rather than laid out
     * again, so they cost nothing until a repagination.
     */
    const val PAGES_BEHIND = 8

    /**
     * How close to the forward edge the reader gets before more is laid out.
     *
     * Three page turns of warning. The extension must be finished before the reader
     * arrives, because an extension that happened on the turn itself would be a
     * stutter at every seam — and a stutter at a seam is exactly the thing a reader
     * would notice.
     */
    const val PREFETCH_MARGIN = 3

    /**
     * How far back a window starts, in characters.
     *
     * [PAGES_BEHIND] pages' worth, at an estimate of what a page holds. The estimate
     * decides only where laying out begins; the reader is always placed by
     * `pageContaining` against real page breaks, so an estimate that is out by a third
     * costs a few pages of room and nothing else.
     */
    fun charsBehind(viewport: Viewport, settings: TypographySettings): Int =
        PAGES_BEHIND * Measure.charsPerPage(viewport, settings)

    /** Whether the window has more chapter ahead and the reader is nearly at its edge. */
    fun wantsForwardExtension(w: WindowedPages): Boolean =
        w.next != null && w.pageIndex >= w.pages.size - 1 - PREFETCH_MARGIN

    /** The end of the chapter, which is the only thing that may open the next one. */
    fun atChapterEnd(w: WindowedPages): Boolean =
        w.next == null && w.pageIndex >= w.pages.lastIndex

    /** The first character of the chapter, likewise for the previous one. */
    fun atChapterStart(w: WindowedPages): Boolean =
        w.start == TextAnchor(0, 0) && w.pageIndex <= 0

    /**
     * A window around [at], or around the start of the chapter when it is null.
     *
     * Null is a deliberate jump — the Contents sheet, or crossing a chapter boundary
     * forwards — and a jump lands on the chapter's first page with the header above
     * it, which is why the window starts at the chapter's first character rather than
     * behind a place nobody asked for.
     */
    suspend fun openAt(
        chapter: Chapter,
        at: ReadingPosition?,
        charsBehind: Int,
        lay: suspend (TextAnchor, Int) -> PageWindow,
    ): WindowedPages {
        val offset =
            if (at == null) 0 else chapter.offsetOf(at.blockIndex, at.charOffset)
        val start = chapter.cursorAt((offset - charsBehind).coerceAtLeast(0))
        return laidOutFrom(chapter, start, offset, lay)
    }

    /**
     * The same window again, after something it depends on changed.
     *
     * From [WindowedPages.start] and not from a cursor worked out afresh: see the
     * note on that field for what recomputing it would do to a reader holding down
     * the type stepper.
     */
    suspend fun relaidOut(
        chapter: Chapter,
        w: WindowedPages,
        lay: suspend (TextAnchor, Int) -> PageWindow,
    ): WindowedPages {
        val page = w.pages.getOrNull(w.pageIndex)?.slices?.firstOrNull()
        val offset =
            if (page == null) chapter.offsetOf(w.start.blockIndex, w.start.charOffset)
            else chapter.offsetOf(page.blockIndex, page.startChar)
        return laidOutFrom(chapter, w.start, offset, lay)
    }

    /**
     * More pages, appended.
     *
     * A pure append, which is what makes a forward seam invisible: no page the reader
     * has seen is laid out a second time and the page they are on does not move. The
     * front is then trimmed so a reader working through a long chapter does not
     * accumulate it — that is the problem this sits next to, not one it may create —
     * and a trim is exact: *k* pages off the front and *k* off the index leaves the
     * reader looking at the same page.
     */
    suspend fun extendedForward(
        w: WindowedPages,
        lay: suspend (TextAnchor, Int) -> PageWindow,
    ): WindowedPages {
        val from = w.next ?: return w
        val more = lay(from, PAGES_AHEAD)
        return trimmedFront(
            w.copy(pages = w.pages + more.pages, next = more.next),
        )
    }

    /**
     * A page turn backwards off the front of the window.
     *
     * This re-tiles the text from a new start, and it is the only operation here that
     * does. It has to: pages tile the text, so the pages *before* a place must end
     * exactly at it, and pagination only runs forwards. The one exact prepend
     * available would leave a deliberately short page at the seam, which is the most
     * visible thing a reader could be shown.
     *
     * So the page the reader is given is **the page holding the character immediately
     * before the one their page began at**. That choice is the whole of the
     * guarantee:
     *
     * - It really moves backwards: that page begins before they did.
     * - **Nothing is skipped.** It runs to at least where they were, so no text falls
     *   between the page they left and the page they are given. Handing them the page
     *   *ending* at or before their old start would have been the other way round — a
     *   turn that jumped up to a page too far back, leaving text they could only find
     *   again by turning forwards.
     * - The cost is an overlap of at most one page: the top of what they were reading
     *   appears at the foot of what they are given. That is the mildest artifact
     *   available here, it is text they were looking at a moment ago, and it happens
     *   once per [PAGES_BEHIND] pages of *backward* travel — never on a forward turn,
     *   never on a type-size change.
     *
     * Never speculative, for the same reason: moving the page under a reader who did
     * not ask for it is the one thing a prefetch must not do.
     */
    suspend fun turnedBack(
        chapter: Chapter,
        w: WindowedPages,
        charsBehind: Int,
        lay: suspend (TextAnchor, Int) -> PageWindow,
    ): WindowedPages {
        if (atChapterStart(w)) return w
        val head = w.pages.firstOrNull()?.slices?.firstOrNull()
        val was =
            if (head == null) chapter.offsetOf(w.start.blockIndex, w.start.charOffset)
            else chapter.offsetOf(head.blockIndex, head.startChar)
        if (was <= 0) return w

        // Doubled if the estimate left no room, rather than trusted once: a re-anchor
        // that landed the reader back on page zero would re-tile the chapter and give
        // them nothing for it, and the next tap would do it again.
        var reach = charsBehind.coerceAtLeast(1)
        repeat(RE_ANCHOR_ATTEMPTS) {
            val start = chapter.cursorAt((was - reach).coerceAtLeast(0))
            val out = laidOutFrom(chapter, start, was - 1, lay)
            if (out.pageIndex > 0 || start == TextAnchor(0, 0)) return out
            reach *= 2
        }
        return w
    }

    // ------------------------------------------------------------- the plumbing

    /** How many times a re-anchor reaches further back before giving up. */
    private const val RE_ANCHOR_ATTEMPTS = 4

    /** How many times a window is extended to reach a place that fell past its end. */
    private const val COVER_ATTEMPTS = 4

    /**
     * Lays out a window from [start] and puts the reader at [offset] in it.
     *
     * The window is extended until it holds the offset. It normally does first time —
     * the start is chosen [charsBehind] characters back, and the budget is wider than
     * that — but the distance back is an estimate and a place the reader cannot be put
     * is worse than a run or two of extra work.
     */
    private suspend fun laidOutFrom(
        chapter: Chapter,
        start: TextAnchor,
        offset: Int,
        lay: suspend (TextAnchor, Int) -> PageWindow,
    ): WindowedPages {
        var laid = lay(start, PAGES_BEHIND + PAGES_AHEAD)
        var pages = laid.pages
        var attempts = 0
        while (!covers(chapter, start, laid.next, offset) &&
            laid.next != null && attempts++ < COVER_ATTEMPTS
        ) {
            val more = lay(laid.next!!, PAGES_AHEAD)
            pages = pages + more.pages
            laid = more
        }
        val cursor = chapter.cursorAt(offset)
        return WindowedPages(
            start = start,
            pages = pages,
            next = laid.next,
            pageIndex = pages.pageContaining(
                ReadingPosition(chapter.index, cursor.blockIndex, cursor.charOffset),
            ).coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
        )
    }

    /**
     * Whether a window running from [start] to [next] holds [offset].
     *
     * Asked in characters rather than by looking for the position in the pages,
     * because `pageContaining` answers 0 both for "the first page" and for "not here
     * at all" — it falls back rather than throwing, deliberately, so that a position
     * saved before a book was reprocessed loses the place instead of crashing. Using
     * that answer to decide whether to extend would stop extending at exactly the
     * moment the reader was off the end.
     */
    private fun covers(
        chapter: Chapter,
        start: TextAnchor,
        next: TextAnchor?,
        offset: Int,
    ): Boolean {
        val from = chapter.offsetOf(start.blockIndex, start.charOffset)
        if (offset < from) return true
        val to = next ?: return true
        return offset < chapter.offsetOf(to.blockIndex, to.charOffset)
    }

    private fun trimmedFront(w: WindowedPages): WindowedPages {
        val drop = w.pageIndex - PAGES_BEHIND
        if (drop <= 0) return w
        val kept = w.pages.drop(drop)
        val head = kept.firstOrNull()?.slices?.firstOrNull() ?: return w
        return w.copy(
            start = TextAnchor(head.blockIndex, head.startChar),
            pages = kept,
            pageIndex = w.pageIndex - drop,
        )
    }
}
