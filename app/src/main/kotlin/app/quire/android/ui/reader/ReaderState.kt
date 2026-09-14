package app.quire.android.ui.reader

import app.quire.android.ui.theme.ReaderFont
import app.quire.core.model.Chapter
import app.quire.core.model.ReadingPosition
import app.quire.core.paginate.ChapterHeading
import app.quire.core.paginate.Page
import app.quire.core.paginate.TypographySettings
import app.quire.core.paginate.pageContaining
import app.quire.core.paginate.startPosition
import app.quire.core.reading.Selection
import app.quire.core.reading.TextAnchor
import app.quire.core.reading.TextSpan

/** Which overlay, if any, is covering the page. */
enum class ReaderOverlay { NONE, CONTENTS, TYPOGRAPHY, BOOKMARK }

/**
 * What the chapter header's small line reads, for a chapter at [index].
 *
 * One expression, because four places need it and one of them — deciding whether the
 * chapter's own heading already says it — has to compare against exactly the string
 * the header draws.
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
    val ch = chapter ?: return true
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
    /** Where the long press landed; the drag moves the other end. */
    val selectionAnchor: TextAnchor? = null,
    /** Saved highlights for the open chapter, drawn on whichever page shows them. */
    val highlights: List<TextSpan> = emptyList(),
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

    /**
     * What the header's small line reads.
     *
     * Here rather than at each site that needs it, because three of them had their
     * own copy of the expression and a fourth has to compare against it.
     */
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
     */
    fun repaginated(
        state: ReaderState,
        pages: List<Page>,
        preferences: ReaderPreferences,
    ): ReaderState {
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

    /** Opens a chapter at a given position, or at its start. */
    fun openedChapter(
        state: ReaderState,
        chapter: Chapter,
        pages: List<Page>,
        at: ReadingPosition?,
    ): ReaderState = withPendingTurnsApplied(
        state.copy(
            loading = false,
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            chapter = chapter,
            pages = pages,
            pageIndex = at?.let { pages.pageContaining(it) } ?: 0,
        )
    )

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
        return state.copy(
            selection = TextSpan.of(start, end),
            selectionAnchor = start,
            // Chrome would cover the passage being chosen.
            chromeVisible = false,
        )
    }

    /** A drag after the press: the fixed end stays, the other follows the finger. */
    fun selectionExtended(state: ReaderState, to: TextAnchor): ReaderState {
        val from = state.selectionAnchor ?: return state
        return state.copy(selection = TextSpan.of(from, to))
    }

    fun selectionCleared(state: ReaderState): ReaderState =
        if (state.selection == null && state.selectionAnchor == null) state
        else state.copy(selection = null, selectionAnchor = null)

    fun withOverlay(state: ReaderState, overlay: ReaderOverlay): ReaderState =
        state.copy(overlay = overlay, chromeVisible = overlay != ReaderOverlay.NONE || state.chromeVisible)

    fun fontSizeChanged(state: ReaderState, delta: Float): ReaderPreferences {
        val next = (state.preferences.fontSizeSp + delta)
            .coerceIn(ReaderPreferences.MIN_SIZE, ReaderPreferences.MAX_SIZE)
        return state.preferences.copy(fontSizeSp = next)
    }
}
