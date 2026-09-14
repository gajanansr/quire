package app.quire.android.ui.reader

import app.quire.android.data.SavedHighlight
import app.quire.android.ui.theme.HighlightColour
import app.quire.android.ui.theme.ReaderFont
import app.quire.core.model.Chapter
import app.quire.core.model.ReadingPosition
import app.quire.core.paginate.ChapterHeading
import app.quire.core.paginate.Page
import app.quire.core.paginate.TypographySettings
import app.quire.core.paginate.pageContaining
import app.quire.core.paginate.startPosition
import app.quire.core.reading.Selection
import app.quire.core.reading.SelectionEdge
import app.quire.core.reading.TextAnchor
import app.quire.core.reading.TextSpan

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
fun showsChapterHeaderFor(chapter: Chapter?, pageIndex: Int): Boolean {
    if (pageIndex != 0) return false
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
    val pageCount: Int get() = pages.size

    val currentPage: Page? get() = pages.getOrNull(pageIndex)

    val atFirstPage: Boolean get() = pageIndex <= 0
    val atLastPage: Boolean get() = pageIndex >= pages.lastIndex

    val atBookStart: Boolean get() = chapterIndex == 0 && atFirstPage
    val atBookEnd: Boolean get() = chapterIndex >= chapterCount - 1 && atLastPage

    /** The canonical position: never a page number. */
    val position: ReadingPosition
        get() = currentPage?.startPosition(chapterIndex) ?: ReadingPosition(chapterIndex, 0, 0)

    /**
     * Progress through the whole book.
     *
     * Built from the chapter's own offset plus how far into it the current page
     * starts, so it stays meaningful across chapters rather than resetting.
     */
    val progress: Double
        get() {
            val ch = chapter ?: return 0.0
            if (bookTotalChars <= 0) return 0.0
            val within = currentPage?.slices?.firstOrNull()?.startChar ?: 0
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
        get() = showsChapterHeaderFor(chapter, pageIndex)

    val hasSelection: Boolean get() = selection != null && selection.isEmpty.not()

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
object ReaderTransitions {

    /** Advances one page, returning null when the next page is in another chapter. */
    fun nextPage(state: ReaderState): ReaderState? =
        if (state.atLastPage) null else state.copy(pageIndex = state.pageIndex + 1)

    fun previousPage(state: ReaderState): ReaderState? =
        if (state.atFirstPage) null else state.copy(pageIndex = state.pageIndex - 1)

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
        pages: List<Page>,
        preferences: ReaderPreferences,
    ): ReaderState {
        if (state.chapter !== chapter) return state.copy(preferences = preferences)
        val anchor = state.position
        return withPendingTurnsApplied(
            state.copy(
                pages = pages,
                pageIndex = pages.pageContaining(anchor)
                    .coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
                preferences = preferences,
            )
        )
    }

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
        pages: List<Page>,
        at: ReadingPosition?,
    ): ReaderState {
        val opened = state.copy(
            loading = false,
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            chapter = chapter,
            pages = pages,
            pageIndex = at?.let { pages.pageContaining(it) } ?: 0,
        )
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

    fun withOverlay(state: ReaderState, overlay: ReaderOverlay): ReaderState =
        state.copy(overlay = overlay, chromeVisible = overlay != ReaderOverlay.NONE || state.chromeVisible)

    fun fontSizeChanged(state: ReaderState, delta: Float): ReaderPreferences {
        val next = (state.preferences.fontSizeSp + delta)
            .coerceIn(ReaderPreferences.MIN_SIZE, ReaderPreferences.MAX_SIZE)
        return state.preferences.copy(fontSizeSp = next)
    }
}
