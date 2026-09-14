package app.quire.android.ui.reader

import app.quire.android.data.SavedHighlight
import app.quire.android.ui.theme.HighlightColour
import app.quire.android.ui.theme.ReaderFont
import app.quire.core.model.Chapter
import app.quire.core.model.ReadingPosition
import app.quire.core.paginate.ChapterHeading
import app.quire.core.paginate.Page
import app.quire.core.paginate.TypographySettings
import app.quire.core.paginate.Viewport
import app.quire.core.paginate.startPosition
import app.quire.core.reading.ReadingEstimates
import app.quire.core.reading.Selection
import app.quire.core.reading.SelectionEdge
import app.quire.core.reading.TextAnchor
import app.quire.core.reading.TextSpan
import kotlin.math.roundToInt

/**
 * What a window's pages were measured against.
 *
 * Pages measured at one type size or one viewport are wrong at another — not merely
 * ugly: reading position is resolved by finding which page holds a character offset,
 * so a page list that does not belong to the current typography puts the reader on
 * the wrong page, and the renderer draws more lines than were budgeted and clips the
 * last one off.
 *
 * Carried on the state so the Reader can ask **"are the pages in hand still the right
 * pages?"** before it extends them. Without it, a reader who tapped A+ while near the
 * end of the window had the extension — laid out at the new size — appended to pages
 * laid out at the old one, and whichever coroutine finished last won.
 */
data class LayoutKey(val viewport: Viewport, val settings: TypographySettings)

/** Which overlay, if any, is covering the page. */
enum class ReaderOverlay { NONE, CONTENTS, TYPOGRAPHY, BOOKMARK }

/**
 * What the chapter header's small line reads, for a chapter at [index].
 *
 * One expression. The header drew it and the share card fell back to it, each with
 * its own copy; the third caller — deciding whether the chapter's own heading already
 * says it — has to compare against exactly the string the header draws, and two
 * copies of a string are two chances for that comparison to be against the wrong one.
 */
fun chapterLabelFor(index: Int): String = "Chapter ${index + 1}"

/**
 * Whether the Reader draws its header above [chapter] on page [pageIndex].
 *
 * A free function, not only a property of the open state, because the question has
 * to be answerable about a chapter that is *not* open yet: the paginator has to
 * budget the header's height into the first page before the chapter it is
 * paginating reaches the state. Asking the open state answered for the chapter being
 * left, at the page index the reader was standing on — see [ReaderLayout].
 */
fun showsChapterHeaderFor(
    chapter: Chapter?,
    pageIndex: Int,
    /**
     * Where the window holding this page begins.
     *
     * Page zero of a *window* is not page zero of the *chapter*. A chapter too long
     * to lay out at once is laid out around wherever the reader is standing, so the
     * first page in hand is usually somewhere in the middle — and drawing a chapter
     * header above it would put "Chapter 1" and a sink in the middle of a sentence,
     * which is the loudest way a chunk boundary could announce itself.
     */
    windowStart: TextAnchor = TextAnchor(0, 0),
): Boolean {
    if (pageIndex != 0 || windowStart != TextAnchor(0, 0)) return false
    // No chapter, no chapter header. This used to answer `true`, and the Reader draws
    // the header above the guard that returns early when there is nothing to draw —
    // so for the whole pre-pagination window a reader resuming in chapter 12 was shown
    // a sink and the words "Chapter 1". Waiting for the saved typography lengthened
    // that window by a database read, which is what made it worth noticing.
    val ch = chapter ?: return false
    // The label of the chapter being asked about, not of whichever is open.
    return !ChapterHeading.repeatsHeader(ch.blockTexts, ch.title, chapterLabelFor(ch.index))
}

data class ReaderPreferences(
    val font: ReaderFont = ReaderFont.SERIF,
    val fontSizeSp: Float = 19f,
    val justify: Boolean = false,
) {
    /** @param pixelsPerSp the device's sp-to-pixel factor; see [TypographySettings]. */
    fun toSettings(pixelsPerSp: Float) = TypographySettings(
        fontSizeSp = fontSizeSp,
        lineHeightMultiple = LINE_HEIGHT,
        fontKey = font.name.lowercase(),
        justify = justify,
        pixelsPerSp = pixelsPerSp,
    )

    companion object {
        /** The handoff's stepper bounds. */
        const val MIN_SIZE = 15f
        const val MAX_SIZE = 24f
        const val STEP = 1f
        const val LINE_HEIGHT = 1.55f
    }
}

data class ReaderState(
    val loading: Boolean = true,
    val bookId: String = "",
    val bookTitle: String = "",
    /** Named on a share card, which the Reader had no way to fill in before. */
    val bookAuthor: String? = null,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String? = null,
    val chapter: Chapter? = null,
    val pages: List<Page> = emptyList(),
    val pageIndex: Int = 0,
    /**
     * Where the pages in hand begin, and where the chapter carries on past them.
     *
     * [pages] is a **window** over the chapter, not the whole of it: a book with no
     * outline is one chapter, and *The Love Hypothesis* is 8,621 blocks and 565,896
     * characters of it. Laying all of that out on open, and again on every tap of A+,
     * is what made the book hang.
     *
     * These two say where the window sits, and everything that used to ask "am I at
     * the end of the chapter?" has to ask them rather than [atLastPage] — which now
     * means only "at the end of what is laid out". See [ReaderWindow].
     */
    val windowStart: TextAnchor = TextAnchor(0, 0),
    val windowNext: TextAnchor? = null,
    /** What [pages] were measured against, or null before anything has been. */
    val windowLayout: LayoutKey? = null,
    val chromeVisible: Boolean = false,
    val overlay: ReaderOverlay = ReaderOverlay.NONE,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val bookTotalChars: Int = 0,
    /**
     * The passage the reader is choosing, if any.
     *
     * Held here rather than in the page because a selection survives a redraw and a
     * page turn does not survive a selection — while one is live the page-turn
     * gestures step aside, or a drag to extend it would turn the page instead.
     */
    val selection: TextSpan? = null,
    /**
     * The word the long press landed on.
     *
     * Kept whole rather than as a single anchor, because a sweep has to grow *out of*
     * it in both directions. Holding only the word's start — which is what this used
     * to be — meant sweeping backwards selected up to that start and the pressed word
     * quietly dropped out of its own selection.
     */
    val selectionOrigin: TextSpan? = null,
    /**
     * Which handle the finger is holding, and null when it is holding neither.
     *
     * The other end is the anchor and must not move for as long as this is set. It
     * can change mid-drag: dragging one handle past the other makes the held handle
     * the opposite end, and the state has to agree with the finger or the next move
     * pins the wrong side.
     */
    val selectionEdge: SelectionEdge? = null,
    /** Saved highlights for the open chapter, drawn on whichever page shows them. */
    val highlights: List<SavedHighlight> = emptyList(),
    /**
     * The colour the next highlight is made in.
     *
     * Session state, and inherited rather than picked: the action bar offers one
     * Highlight button, not six, because it sits over the words the reader is looking
     * at. Choosing a colour on a mark they have already made sets this too, so a
     * reader who colour-codes chooses once and every mark after it follows.
     */
    val highlightColour: HighlightColour = HighlightColour.DEFAULT,
    /**
     * The mark whose options are open, by row id.
     *
     * The id and not the mark itself, so that the swatch row shows the colour the
     * *database* now holds. Recolouring re-emits the chapter's highlights, and a copy
     * held here would keep ringing the colour the reader just moved away from.
     */
    val editingHighlightId: Long? = null,
    /**
     * Page turns asked for before there were any pages to turn, net of direction.
     *
     * A large book spends seconds being paginated, and every tap in that window used
     * to vanish: with no pages, "next page" reports that this is the last one, which
     * the Reader read as a chapter boundary and abandoned. The reader taps, nothing
     * moves, and the first tap that works is the first one after pagination finished
     * — which is what "initially page change doesn't work at all" was.
     */
    val pendingTurns: Int = 0,
) {
    /** How many pages are laid out — not how long the chapter is. See [windowStart]. */
    val pageCount: Int get() = pages.size

    /**
     * The line under the progress bar.
     *
     * It read `"38% · page 12 of 719"`, and the denominator was the chapter laid out
     * whole at the reader's type size. A chapter is now laid out a window at a time,
     * so that number would be the window's — "page 5 of 20", resetting as the reader
     * went, and reporting a chunk boundary out loud.
     *
     * Counted in printed pages instead: [ReadingEstimates] divides the book's
     * characters by a paperback page, which is the same measure Book Details already
     * states a book's length in. **"about" is not decoration** — it is the difference
     * between an estimate and a claim, and the reader can check this one against the
     * spine of the book. For the 565,896-character novel this was built for it reads
     * 315, which is exactly what that PDF has.
     *
     * It also stops changing when the type size does, which the old number did on
     * every tap of the stepper.
     */
    val readingLine: String
        get() {
            val percent = (progress * 100).roundToInt()
            if (bookTotalChars <= 0) return "$percent%"
            return "$percent% · about page " +
                "${ReadingEstimates.currentPage(bookTotalChars, progress)} of " +
                "${ReadingEstimates.pageCount(bookTotalChars)}"
        }

    val currentPage: Page? get() = pages.getOrNull(pageIndex)

    val atFirstPage: Boolean get() = pageIndex <= 0
    val atLastPage: Boolean get() = pageIndex >= pages.lastIndex

    /** The pages in hand, as the thing that knows how to grow itself. */
    val window: WindowedPages
        get() = WindowedPages(windowStart, pages, windowNext, pageIndex)

    /**
     * The real ends of the chapter, which are not the ends of the pages in hand.
     *
     * [atLastPage] used to be both questions at once, and after windowing it is only
     * the first: a reader at the end of the window is usually in the middle of the
     * chapter. Treating the two as the same would drop the reader into the next
     * chapter every twelve pages.
     */
    val atChapterEnd: Boolean get() = windowNext == null && atLastPage
    val atChapterStart: Boolean
        get() = windowStart == TextAnchor(0, 0) && atFirstPage

    val atBookStart: Boolean get() = chapterIndex == 0 && atChapterStart
    val atBookEnd: Boolean get() = chapterIndex >= chapterCount - 1 && atChapterEnd

    /** The canonical position: never a page number. */
    val position: ReadingPosition
        get() = currentPage?.startPosition(chapterIndex) ?: ReadingPosition(chapterIndex, 0, 0)

    /**
     * Progress through the whole book.
     *
     * Built from the chapter's own offset plus how far into it the current page
     * starts, so it stays meaningful across chapters rather than resetting.
     *
     * "How far into it" is the offset into the **chapter**, not into the block the
     * page happens to open on. It used to be `slice.startChar`, which is measured
     * from the start of that one block: on a book of many small chapters the chapter
     * offsets carried the number and it looked right, and on a book with no outline —
     * one chapter of 8,621 blocks — it never exceeded the length of a single
     * paragraph. A reader three hundred screens into a 566,000-character novel was
     * shown 0%, and the widget and Book Details were shown the same 0%.
     */
    val progress: Double
        get() {
            val ch = chapter ?: return 0.0
            if (bookTotalChars <= 0) return 0.0
            val first = currentPage?.slices?.firstOrNull()
            val within =
                if (first == null) 0 else ch.offsetOf(first.blockIndex, first.startChar)
            val consumed = ch.startCharOffset + within.coerceAtMost(ch.charCount)
            return (consumed.toDouble() / bookTotalChars).coerceIn(0.0, 1.0)
        }

    /** What the header's small line reads for the open chapter. */
    val chapterLabel: String get() = chapterLabelFor(chapterIndex)

    /**
     * Whether to draw the chapter header above the text.
     *
     * Suppressed when the chapter's own opening already says the same thing — most
     * EPUBs open a chapter with an `<h1>` of its title, and drawing both prints the
     * title twice. See [ChapterHeading] for why matching on the first block's exact
     * characters was not enough on a real book.
     */
    val showsChapterHeader: Boolean
        get() = showsChapterHeaderFor(chapter, pageIndex, windowStart)

    val hasSelection: Boolean get() = selection != null && selection.isEmpty.not()

    /**
     * The mark the options are open on, or null.
     *
     * Looked up rather than stored. A highlight removed from another screen — or by
     * the Remove button itself — simply stops being found, which closes the options
     * instead of leaving them pointing at a row that is gone.
     */
    val editingHighlight: SavedHighlight?
        get() = editingHighlightId?.let { id -> highlights.firstOrNull { it.id == id } }

    /** The words the reader has chosen, ready to highlight, copy or share. */
    val selectedText: String
        get() {
            val span = selection ?: return ""
            val ch = chapter ?: return ""
            return Selection.textOf(ch.blockTexts, span)
        }

    /**
     * Every word the current page shows.
     *
     * Not the same thing as [currentPageSnippet], and the difference is a real bug:
     * sharing a page reused the snippet, which is deliberately a single block, so a
     * reader who shared the page they were on got its first paragraph and nothing
     * else — looking, on the card, exactly like a complete quotation.
     *
     * A bookmark row wants one recognisable line. A share wants the page. Two
     * properties, because they are two questions.
     */
    val currentPageText: String
        get() = drawnBlocks().joinToString("\n\n") { it.trim() }

    /** The text of each block this page draws, already cut to the page's own slices. */
    private fun drawnBlocks(): List<String> {
        val ch = chapter ?: return emptyList()
        val page = currentPage ?: return emptyList()
        return page.slices.mapNotNull { slice ->
            val text = ch.blockTexts.getOrNull(slice.blockIndex) ?: return@mapNotNull null
            if (text.isEmpty() || slice.length == 0) null
            else text.substring(
                slice.startChar.coerceIn(0, text.length),
                slice.endChar.coerceIn(0, text.length),
            )
        }.filter { it.isNotBlank() }
    }

    /**
     * The text the current page opens with, for a bookmark's snippet.
     *
     * Taken from the page rather than the whole chapter so the saved words are the
     * ones actually on screen when the reader marked it.
     *
     * The first block on a page is often the running page number, and a bookmark
     * list reading "38", "112", "204" tells a reader nothing about what they marked.
     * So the first block carrying *words* wins — but only as a preference: if the
     * page really is nothing but a number, that number is still what was there, and
     * a poor snippet beats an empty one.
     */
    val currentPageSnippet: String
        get() {
            val drawn = drawnBlocks()
            return (drawn.firstOrNull { it.any(Char::isLetter) } ?: drawn.firstOrNull())
                .orEmpty()
                .trim()
        }

    /**
     * A tap in the middle of the page toggles the chrome — unless an overlay is
     * open, which the handoff says must be dismissed first.
     */
    val tapTogglesChrome: Boolean get() = overlay == ReaderOverlay.NONE
}

/**
 * Pure transitions over reader state.
 *
 * Kept as free functions on the state so page turns, chapter boundaries and
 * repagination can be tested without a device — this is where an off-by-one
 * silently skips a page of someone's book.
 */
/**
 * Takes on a window's pages, its bounds and the place it put the reader.
 *
 * One function, because those four fields are one fact: a page index that outlives
 * the page list it indexes is how a reader ends up on a page they have never seen,
 * and separate copies at four call sites is four chances to update three of them.
 */
private fun ReaderState.withWindow(window: WindowedPages, layout: LayoutKey?) = copy(
    pages = window.pages,
    pageIndex = window.pageIndex.coerceIn(0, (window.pages.size - 1).coerceAtLeast(0)),
    windowStart = window.start,
    windowNext = window.next,
    windowLayout = layout,
)

object ReaderTransitions {

    /** Advances one page, returning null when the next page is in another chapter. */
    /**
     * A turn also closes the options on a mark.
     *
     * The chapter's highlights are all in hand, not just this page's, so the options
     * would otherwise survive the turn and sit at the foot of the next page offering
     * to recolour a passage that is no longer on it. A swipe past an open picker is
     * the common way to reach that state, since taps are taken by the picker itself.
     */
    fun nextPage(state: ReaderState): ReaderState? =
        if (state.atLastPage) null
        else state.copy(pageIndex = state.pageIndex + 1, editingHighlightId = null)

    fun previousPage(state: ReaderState): ReaderState? =
        if (state.atFirstPage) null
        else state.copy(pageIndex = state.pageIndex - 1, editingHighlightId = null)

    /**
     * Remembers a page turn asked for while there was nothing to turn.
     *
     * Kept as a count and a direction rather than a queue of events: what the reader
     * means by three taps during a long pagination is "three pages on", and replaying
     * three separate turns against a page list that arrives all at once means the
     * same thing.
     */
    fun queuedTurn(state: ReaderState, forward: Boolean): ReaderState =
        state.copy(pendingTurns = state.pendingTurns + if (forward) 1 else -1)

    /**
     * Applies the turns that were asked for before the pages existed.
     *
     * Clamped inside the chapter rather than carried across a boundary. A tap made
     * while the reader could not see what they were turning is not evidence that they
     * wanted the next chapter, and loading one from a queue would move them somewhere
     * they never chose.
     */
    private fun withPendingTurnsApplied(state: ReaderState): ReaderState {
        if (state.pendingTurns == 0) return state
        if (state.pages.isEmpty()) return state
        val target = (state.pageIndex + state.pendingTurns).coerceIn(0, state.pages.lastIndex)
        return state.copy(pageIndex = target, pendingTurns = 0)
    }

    /**
     * Re-pages after a typography change, keeping the reader where they were.
     *
     * The page index is meaningless across a repagination — this is the moment the
     * character-offset position earns its place.
     *
     * [chapter] is the chapter the pages were laid out *for*, and pages laid out for
     * one chapter are discarded rather than written onto another. Laying out a long
     * chapter takes seconds, and the Contents sheet loads a chapter in a coroutine
     * the repagination effect does not cancel — so the reader can be in chapter 10 by
     * the time chapter 3's pages arrive. Accepting them would leave the state
     * describing chapter 10 with chapter 3's page breaks, and `position` would then
     * be a character offset from chapter 3 stamped with chapter 10's index. That gets
     * written to the progress row when the Reader closes, so the next time the book
     * is opened it lands somewhere the reader has never been. The guard is here
     * rather than at the call site because there is no call site that may skip it.
     */
    fun repaginated(
        state: ReaderState,
        chapter: Chapter?,
        window: WindowedPages,
        preferences: ReaderPreferences,
        layout: LayoutKey?,
    ): ReaderState {
        if (state.chapter !== chapter) return state.copy(preferences = preferences)
        // Placed against the state this lands in, not the one the lay-out started
        // from. The window carries the page index it was *built* for, and laying one
        // out takes long enough for a reader to turn a page meanwhile — writing that
        // index back put them silently where they were when it began. On `main` this
        // was `pages.pageContaining(state.position)` for the same reason, and losing
        // it while moving to windows was a regression rather than a change.
        val placed =
            if (chapter == null) window else ReaderWindow.placedAt(chapter, window, state.position)
        return withPendingTurnsApplied(
            state.withWindow(placed, layout).copy(preferences = preferences),
        )
    }

    /**
     * Adopts a window laid out for [chapter] — extended forward, or re-anchored back.
     *
     * Guarded on the chapter for the same reason [repaginated] is, and the guard has
     * to be here rather than at the call site because there is no call site that may
     * skip it: an extension runs in a coroutine, the Contents sheet can load another
     * chapter while it does, and pages from one chapter written onto another leave
     * `position` a character offset from the wrong chapter — which is what gets saved
     * to the progress row when the Reader closes.
     */
    fun windowed(
        state: ReaderState,
        chapter: Chapter?,
        window: WindowedPages,
        layout: LayoutKey?,
    ): ReaderState {
        if (state.chapter !== chapter) return state
        // Deliberately does *not* cash queued turns. This is called from three places
        // and only one of them is a tap: letting it take a turn meant a turn stranded
        // by a dropped run — the carry moved, or the reader moved — was cashed later
        // by a **background prefetch**, moving the page under a reader who had not
        // touched the screen for a dozen pages. The tap path applies them itself, once,
        // through [pendingTurnsApplied], whether its own window was adopted or not.
        return state.withWindow(window, layout)
    }

    /**
     * Cashes the turns queued while a window was being laid out.
     *
     * Called once at the end of the run those taps were made during, and on every
     * path out of it. Clamped inside the pages in hand, as ever: a tap made while the
     * reader could not see what they were turning is not evidence that they wanted the
     * next chapter.
     */
    fun pendingTurnsApplied(state: ReaderState): ReaderState = withPendingTurnsApplied(state)

    /**
     * Opens a chapter at a given position, or at its start.
     *
     * Turns queued during pagination are honoured only when opening *at* a position —
     * which is the book being opened at where it was left, the case those taps were
     * made in. A chapter opened with no position is a deliberate jump: the Contents
     * sheet, or crossing a boundary. Carrying the taps there would take a reader who
     * tapped three times waiting for their own chapter to appear and drop them on
     * page three of a chapter they had only just chosen.
     */
    fun openedChapter(
        state: ReaderState,
        chapter: Chapter,
        window: WindowedPages,
        at: ReadingPosition?,
        layout: LayoutKey? = null,
    ): ReaderState {
        val opened = state.copy(
            loading = false,
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            chapter = chapter,
            // A row id from the chapter being left would resolve against this one's
            // highlights, so the options would reopen on whichever mark happened to
            // share the number. withWindow does not clear it, so it is cleared here.
            editingHighlightId = null,
        ).withWindow(window, layout)
        return if (at == null) opened.copy(pendingTurns = 0)
        else withPendingTurnsApplied(opened)
    }

    fun withChrome(state: ReaderState, visible: Boolean): ReaderState =
        state.copy(chromeVisible = visible)

    /** A centre tap: toggles chrome, or is swallowed by an open overlay. */
    fun tapped(state: ReaderState): ReaderState =
        if (!state.tapTogglesChrome) state
        else state.copy(chromeVisible = !state.chromeVisible)

    /**
     * A long press: take the word under the finger.
     *
     * The word, not the character — a fingertip covers several characters, and a
     * selection that starts as one letter reads as a misfire rather than a start.
     */
    fun selectionStarted(state: ReaderState, at: TextAnchor): ReaderState {
        val text = state.chapter?.blockTexts?.getOrNull(at.blockIndex) ?: return state
        val word = app.quire.core.reading.WordBoundary.expand(text, at.charOffset)
        if (word.isEmpty()) return state

        val start = TextAnchor(at.blockIndex, word.first)
        val end = TextAnchor(at.blockIndex, word.last + 1)
        val origin = TextSpan.of(start, end)
        return state.copy(
            selection = origin,
            selectionOrigin = origin,
            selectionEdge = null,
            // Chrome would cover the passage being chosen.
            chromeVisible = false,
            // And so would the options: both bars live at the foot of the page, and
            // two of them there at once is the page covered by its own controls.
            editingHighlightId = null,
        )
    }

    /**
     * A drag while the press is still held: the pressed word stays in, and the
     * selection grows out to whole words in the direction of travel.
     *
     * Word granularity here and character granularity on the handles is not an
     * inconsistency, it is the division of labour Android uses. A moving thumb cannot
     * aim at a letter, so a character-accurate sweep reads as jitter; a reader reaches
     * for a handle precisely when they want the letter the sweep overshot.
     */
    fun selectionExtended(state: ReaderState, to: TextAnchor): ReaderState {
        val origin = state.selectionOrigin ?: return state
        val texts = state.chapter?.blockTexts ?: return state
        return state.copy(selection = Selection.sweptTo(texts, origin, to))
    }

    /**
     * The reader has taken hold of one of the handles.
     *
     * Chrome goes, for the same reason a long press takes it away: the top and bottom
     * bars are exactly where a selection near the edge of the page lives.
     */
    fun handleGrabbed(state: ReaderState, edge: SelectionEdge): ReaderState =
        if (state.selection == null) state
        else state.copy(selectionEdge = edge, chromeVisible = false)

    /**
     * A held handle following the finger, character by character.
     *
     * Both halves of the answer are used: the new span, and which end the finger is
     * now on — those differ the moment a handle is dragged past its partner.
     */
    fun handleMoved(state: ReaderState, to: TextAnchor): ReaderState {
        val span = state.selection ?: return state
        val edge = state.selectionEdge ?: return state
        val dragged = Selection.movingEdge(span, edge, to)
        return state.copy(selection = dragged.span, selectionEdge = dragged.edge)
    }

    /** The finger lifted. The passage stays exactly where it was left. */
    fun handleReleased(state: ReaderState): ReaderState =
        if (state.selectionEdge == null) state else state.copy(selectionEdge = null)

    /**
     * A tap while a passage is selected.
     *
     * A tap on the passage itself keeps it. That is not politeness: the handles put
     * two controls on top of the selected words, so the selected words are exactly
     * where a thumb goes, and clearing on that tap is the specific way the old
     * behaviour destroyed work with no way back. A tap anywhere else clears, which is
     * what every other Android app does and what a reader will try first.
     *
     * A null [at] is a tap the page could not resolve — a margin, or a gap below the
     * last paragraph — and clears.
     */
    fun tappedWhileSelecting(state: ReaderState, at: TextAnchor?): ReaderState {
        val span = state.selection ?: return state
        return if (at != null && at in span) state else selectionCleared(state)
    }

    fun selectionCleared(state: ReaderState): ReaderState =
        if (state.selection == null && state.selectionOrigin == null &&
            state.selectionEdge == null
        ) {
            state
        } else {
            state.copy(selection = null, selectionOrigin = null, selectionEdge = null)
        }

    /**
     * A tap on a saved highlight: open its options, or close whatever was open.
     *
     * One entry point for both, because they are one gesture. While the options are
     * open every tap goes through here — a tap on another mark moves to it, and a tap
     * anywhere else closes — so there is no state in which a tap does nothing and the
     * reader is left prodding at a row of swatches that will not go away.
     *
     * A second tap on the mark that is already open closes it, which is the gesture a
     * reader will try before they try tapping the page — the options appeared under
     * their finger, so their finger is where they look to put them away.
     *
     * Chrome goes with it, for the same reason a long press takes it away: the bars
     * are exactly where the options sit when the mark is near an edge of the page.
     */
    fun highlightTapped(state: ReaderState, id: Long?): ReaderState {
        val next = if (id != null && id == state.editingHighlightId) null else id
        return if (state.editingHighlightId == next) state
        else state.copy(editingHighlightId = next, chromeVisible = false)
    }

    /**
     * The reader chose a colour for the mark they had open.
     *
     * It also becomes the colour the *next* highlight is made in. That is the whole
     * of "choose the colour before you mark": the action bar keeps one Highlight
     * button rather than six, and a reader who colour-codes sets the colour once on a
     * mark they can see and every mark after it follows.
     *
     * The options stay open. A colour is a thing people compare — the reader has just
     * put a wash over their own sentence and wants to look at it — and a picker that
     * dismisses itself on the first tap makes trying the next one a fresh gesture.
     */
    fun highlightRecoloured(state: ReaderState, colour: HighlightColour): ReaderState =
        state.copy(highlightColour = colour)

    /**
     * The chapter changed, so no mark from the old one can still be open.
     *
     * Turning a page does not close the options — the mark may well still be on
     * screen — but leaving a chapter does: the id would resolve against a different
     * chapter's highlights, which is how a reader ends up recolouring a passage they
     * cannot see.
     */
    fun highlightOptionsClosed(state: ReaderState): ReaderState =
        if (state.editingHighlightId == null) state else state.copy(editingHighlightId = null)

    fun withOverlay(state: ReaderState, overlay: ReaderOverlay): ReaderState =
        state.copy(overlay = overlay, chromeVisible = overlay != ReaderOverlay.NONE || state.chromeVisible)

    fun fontSizeChanged(state: ReaderState, delta: Float): ReaderPreferences {
        val next = (state.preferences.fontSizeSp + delta)
            .coerceIn(ReaderPreferences.MIN_SIZE, ReaderPreferences.MAX_SIZE)
        return state.preferences.copy(fontSizeSp = next)
    }
}
