package app.folio.android.ui.reader

import app.folio.android.ui.theme.ReaderFont
import app.folio.core.model.Chapter
import app.folio.core.model.ContentBlock
import app.folio.core.model.ReadingPosition
import app.folio.core.model.plainText
import app.folio.core.paginate.Page
import app.folio.core.paginate.TypographySettings
import app.folio.core.paginate.pageContaining
import app.folio.core.paginate.startPosition

/** Which overlay, if any, is covering the page. */
enum class ReaderOverlay { NONE, CONTENTS, TYPOGRAPHY, BOOKMARK }

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
     * Whether to draw the chapter header above the text.
     *
     * Suppressed when the chapter's own first block is a heading saying the same
     * thing — most EPUBs open a chapter with an `<h1>` of its title, and drawing
     * both prints the title twice.
     */
    val showsChapterHeader: Boolean
        get() {
            if (pageIndex != 0) return false
            val first = chapter?.blocks?.firstOrNull() ?: return true
            if (first !is ContentBlock.Heading) return true
            val heading = first.plainText.trim()
            val title = chapterTitle?.trim().orEmpty()
            return !heading.equals(title, ignoreCase = true)
        }

    /**
     * The text the current page opens with, for a bookmark's snippet.
     *
     * Taken from the page rather than the whole chapter so the saved words are the
     * ones actually on screen when the reader marked it.
     */
    val currentPageSnippet: String
        get() {
            val ch = chapter ?: return ""
            val page = currentPage ?: return ""
            return page.slices.asSequence()
                .mapNotNull { slice ->
                    val block = ch.blocks.getOrNull(slice.blockIndex) ?: return@mapNotNull null
                    val text = block.plainText
                    if (text.isEmpty() || slice.length == 0) null
                    else text.substring(
                        slice.startChar.coerceIn(0, text.length),
                        slice.endChar.coerceIn(0, text.length),
                    )
                }
                .firstOrNull { it.isNotBlank() }
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
        return state.copy(
            pages = pages,
            pageIndex = pages.pageContaining(anchor).coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
            preferences = preferences,
        )
    }

    /** Opens a chapter at a given position, or at its start. */
    fun openedChapter(
        state: ReaderState,
        chapter: Chapter,
        pages: List<Page>,
        at: ReadingPosition?,
    ): ReaderState = state.copy(
        loading = false,
        chapterIndex = chapter.index,
        chapterTitle = chapter.title,
        chapter = chapter,
        pages = pages,
        pageIndex = at?.let { pages.pageContaining(it) } ?: 0,
    )

    fun withChrome(state: ReaderState, visible: Boolean): ReaderState =
        state.copy(chromeVisible = visible)

    /** A centre tap: toggles chrome, or is swallowed by an open overlay. */
    fun tapped(state: ReaderState): ReaderState =
        if (!state.tapTogglesChrome) state
        else state.copy(chromeVisible = !state.chromeVisible)

    fun withOverlay(state: ReaderState, overlay: ReaderOverlay): ReaderState =
        state.copy(overlay = overlay, chromeVisible = overlay != ReaderOverlay.NONE || state.chromeVisible)

    fun fontSizeChanged(state: ReaderState, delta: Float): ReaderPreferences {
        val next = (state.preferences.fontSizeSp + delta)
            .coerceIn(ReaderPreferences.MIN_SIZE, ReaderPreferences.MAX_SIZE)
        return state.preferences.copy(fontSizeSp = next)
    }
}
