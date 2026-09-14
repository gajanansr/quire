package app.quire.android.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import app.quire.android.data.BookRepository
import app.quire.android.data.HabitRepository
import app.quire.android.habit.SessionTracker
import app.quire.android.share.Sharing
import app.quire.android.ui.QuireStrings
import app.quire.android.ui.share.ShareCard
import app.quire.android.ui.theme.QuireThemeName
import app.quire.android.ui.theme.ReaderFont
import app.quire.core.model.Chapter
import app.quire.core.model.ChapterRef
import app.quire.core.model.ReadingPosition
import app.quire.core.paginate.Page
import app.quire.core.paginate.ChapterOpening
import app.quire.core.paginate.Paginator
import app.quire.core.paginate.Viewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads a book, paginates it, and keeps the reader's position saved.
 *
 * Pagination runs on [Dispatchers.Default]: a chapter of a long book takes long
 * enough to drop frames if it happens during composition, and the reader would feel
 * it as a stutter when changing type size.
 *
 * Only the current chapter is paginated. Neighbours are loaded when the reader
 * crosses a boundary rather than up front, so opening a four-hundred-page book
 * costs the same as opening a short one.
 */
@Composable
fun ReaderHost(
    repository: BookRepository,
    habitRepository: HabitRepository,
    bookId: String,
    theme: QuireThemeName,
    onThemeChange: (QuireThemeName) -> Unit,
    onExit: () -> Unit,
    /**
     * Hands a chosen passage up to the share sheet.
     *
     * The Reader used to fire a plain-text intent of its own, which meant the card
     * — the thing anyone would actually post — was reachable only from the bookmark
     * list, at the far end of the journey from where the passage was chosen.
     */
    onShareQuote: (ShareCard.Quote) -> Unit = {},
    /** Fired when a flush pushes the reader over their daily goal. */
    onGoalReached: () -> Unit = {},
    /**
     * Fired with the minutes a finished session credited to today.
     *
     * The one moment there is evidence that this reader intends to come back, which
     * is when Quire is allowed to ask about reminders — see
     * [app.quire.android.notify.ReminderPermission].
     */
    onSessionRecorded: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var state by remember(bookId) { mutableStateOf(ReaderState()) }
    var viewport by remember { mutableStateOf(Viewport(0f, 0f)) }
    var contents by remember(bookId) { mutableStateOf<List<ChapterRef>>(emptyList()) }
    var bookmarked by remember(bookId) { mutableStateOf(false) }

    val composeMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontResolver = LocalFontFamilyResolver.current
    val scope = rememberCoroutineScope()
    val tracker = remember(bookId) { SessionTracker(habitRepository) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val measurer = remember(state.preferences.font, density, fontResolver) {
        ComposeTextMeasurer(composeMeasurer, density, state.preferences.font.family())
    }
    val paginator = remember(measurer) { Paginator(measurer) }
    val pageCache = remember(bookId) { PageCache() }

    // sp -> px for this screen. The paginator reasons in device pixels because the
    // viewport does.
    val pixelsPerSp = with(density) { 1.sp.toPx() }

    /**
     * Paginates, or returns what was paginated before.
     *
     * Every caller has to key on the same things or the cache is worse than none,
     * so the key is built here rather than at each site.
     */
    suspend fun pagesFor(
        chapter: Chapter,
        prefs: ReaderPreferences,
        inset: Float,
        paginate: suspend () -> List<Page>,
    ): List<Page> {
        val key = PageCache.Key(chapter.index, viewport, prefs.toSettings(pixelsPerSp), inset)
        pageCache.get(key)?.let { return it }
        return paginate().also { pageCache.put(key, it) }
    }

    /**
     * Height the chapter header will take on the first page.
     *
     * Estimated rather than measured: it is label, title and spacing at known
     * sizes, and measuring it would mean composing before paginating. Erring
     * generous leaves a little whitespace; erring short clips the last line.
     */
    fun headerInsetPx(): Float {
        if (!state.showsChapterHeader) return 0f
        val body = state.preferences.fontSizeSp * ReaderPreferences.LINE_HEIGHT
        // The sink is the space a chapter opens below — a proportion of the page,
        // because the gap that looks generous on a phone is a rounding error on a
        // tablet. The rest is label, title and the air beneath them.
        return ChapterOpening.sinkPx(viewport.heightPx) +
            with(density) { body.sp.toPx() * 1.2f + body.sp.toPx() * 2.4f + 30.dp.toPx() }
    }

    suspend fun loadChapter(index: Int, at: ReadingPosition?) {
        val entity = repository.find(bookId) ?: return
        val chapter: Chapter = repository.loadChapter(bookId, index) ?: return
        val inset = headerInsetPx()
        val pages = pagesFor(chapter, state.preferences, inset) {
            withContext(Dispatchers.Default) {
                paginator.paginate(
                    chapter, viewport, state.preferences.toSettings(pixelsPerSp), inset,
                )
            }
        }
        state = ReaderTransitions.openedChapter(
            state.copy(
                bookId = bookId,
                bookTitle = entity.title,
                bookAuthor = entity.author,
                chapterCount = entity.chapterCount,
                bookTotalChars = entity.totalChars,
            ),
            chapter, pages, at,
        )
    }

    // Open at the saved position once the viewport is known: paginating against a
    // zero-sized viewport would produce pages that are immediately thrown away.
    LaunchedEffect(bookId, viewport) {
        if (viewport.widthPx <= 0f || viewport.heightPx <= 0f) return@LaunchedEffect
        if (state.chapter == null) {
            val saved = repository.progressOf(bookId)
            loadChapter(saved.chapterIndex, saved)
            tracker.record()
        } else {
            // The viewport changed — a rotation, or the first real measurement.
            val chapter = state.chapter ?: return@LaunchedEffect
            val inset = headerInsetPx()
            val pages = pagesFor(chapter, state.preferences, inset) {
                withContext(Dispatchers.Default) {
                    paginator.paginate(
                        chapter, viewport, state.preferences.toSettings(pixelsPerSp), inset,
                    )
                }
            }
            state = ReaderTransitions.repaginated(state, pages, state.preferences)
        }
    }

    suspend fun persistNow() {
        repository.saveProgress(bookId, state.position, state.progress)
    }

    /**
     * Re-pages after a typography change, keeping the reader in place.
     *
     * Runs off the main thread and applies the new pages in one state update, so
     * changing type size never shows a half-laid-out page.
     */
    fun applyPreferences(next: ReaderPreferences) {
        val chapter = state.chapter
        if (chapter == null || viewport.widthPx <= 0f) {
            state = state.copy(preferences = next)
            return
        }
        scope.launch {
            val inset = if (state.showsChapterHeader) headerInsetPx() else 0f
            val pages = pagesFor(chapter, next, inset) {
                withContext(Dispatchers.Default) {
                    Paginator(
                        ComposeTextMeasurer(composeMeasurer, density, next.font.family()),
                    ).paginate(chapter, viewport, next.toSettings(pixelsPerSp), inset)
                }
            }
            state = ReaderTransitions.repaginated(state, pages, next)
            habitRepository.setReaderPreferences(
                font = next.font.name, sizeSp = next.fontSizeSp, justify = next.justify,
            )
            persistNow()
        }
    }

    /**
     * Commits the reading session and reports a goal newly met.
     *
     * "Newly" matters: the goal screen should appear the moment it is crossed, not
     * every time the reader closes a book for the rest of the day.
     */
    suspend fun flushSession() {
        val before = habitRepository.observeSummary().first()
        val credits = tracker.flush()
        if (credits.isEmpty()) return
        val after = habitRepository.observeSummary().first()
        if (!before.goalMet && after.goalMet) onGoalReached()
        onSessionRecorded(tracker.minutesToday(credits))
    }

    fun persist() {
        val snapshot = state
        scope.launch {
            repository.saveProgress(bookId, snapshot.position, snapshot.progress)
        }
    }

    /**
     * Flushes when the app goes to the background as well as on exit: most reading
     * sessions end with a locked phone, not a back-press, and only committing on a
     * clean exit would lose nearly all of them.
     */
    DisposableEffect(lifecycleOwner, bookId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                runBlocking { flushSession() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Leaving the Reader by any route still commits the session *and* the
            // place. Position used to be saved only by the Reader's own back arrow,
            // which was fine while that was the only way out — a system Back press
            // now closes the Reader too, and it has no way to reach `persistNow`.
            // Doing it here means no caller has to remember.
            runBlocking { flushSession(); persistNow() }
        }
    }

    fun turn(forward: Boolean) {
        val next = if (forward) ReaderTransitions.nextPage(state)
        else ReaderTransitions.previousPage(state)

        if (next != null) {
            state = next
            // A page turn is the signal that reading is still happening; without
            // it the idle timeout would end the session mid-book.
            tracker.record()
            persist()
            return
        }

        // Crossing a chapter boundary.
        val target = if (forward) state.chapterIndex + 1 else state.chapterIndex - 1
        if (target < 0 || target >= state.chapterCount) return
        scope.launch {
            loadChapter(target, null)
            if (!forward) {
                // Entering a chapter backwards should land on its last page, not
                // its first — otherwise turning back skips the whole chapter.
                state = state.copy(pageIndex = (state.pages.size - 1).coerceAtLeast(0))
            }
            persist()
        }
    }

    val context = LocalContext.current

    // Saved highlights for whichever chapter is open. Collected rather than loaded
    // once, so a passage the reader highlights appears under their finger instead of
    // on the next visit.
    LaunchedEffect(bookId, state.chapterIndex) {
        repository.observeHighlights(bookId, state.chapterIndex).collect { spans ->
            state = state.copy(highlights = spans)
        }
    }

    LaunchedEffect(bookId) { contents = repository.chapterIndex(bookId) }

    // Typography is a setting, not a session preference: someone who chose Lora at
    // 22pt should not have to choose it again next time they open a book.
    LaunchedEffect(bookId) {
        val saved = habitRepository.settings()
        state = state.copy(
            preferences = ReaderPreferences(
                font = ReaderFont.entries.firstOrNull { it.name == saved.readerFont }
                    ?: ReaderFont.SERIF,
                fontSizeSp = saved.readerFontSizeSp,
                justify = saved.readerJustify,
            )
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        ReaderScreen(
            onContentSize = { viewport = Viewport(it.width.toFloat(), it.height.toFloat()) },
            state = state,
            onTap = { state = ReaderTransitions.tapped(state) },
            onNextPage = { turn(forward = true) },
            onPreviousPage = { turn(forward = false) },
            onBack = { persist(); onExit() },
            onOpenContents = { state = ReaderTransitions.withOverlay(state, ReaderOverlay.CONTENTS) },
            onOpenTypography = { state = ReaderTransitions.withOverlay(state, ReaderOverlay.TYPOGRAPHY) },
            onBookmark = {
                val snapshot = state
                scope.launch {
                    repository.addBookmark(
                        bookId, snapshot.position, snapshot.currentPageSnippet,
                    )
                    bookmarked = true
                }
            },
            onSharePage = {
                // No selection: offer the page the reader is looking at. The same
                // sheet a chosen passage opens, so both routes look alike.
                onShareQuote(quoteOf(state, state.currentPageText))
            },
            onFinish = { persist(); onExit() },
            onSelectionStart = { state = ReaderTransitions.selectionStarted(state, it) },
            onSelectionExtend = { state = ReaderTransitions.selectionExtended(state, it) },
            onSelectionClear = { state = ReaderTransitions.selectionCleared(state) },
            onHighlight = {
                val snapshot = state
                val span = snapshot.selection
                if (span != null) {
                    scope.launch {
                        repository.addHighlight(
                            bookId = bookId,
                            chapterIndex = snapshot.chapterIndex,
                            span = span,
                            snippet = snapshot.selectedText,
                        )
                        // Cleared only after the row is written, so the passage stays
                        // lit until there is something saved to light it.
                        state = ReaderTransitions.selectionCleared(state)
                        bookmarked = true
                    }
                }
            },
            onShareSelection = {
                onShareQuote(quoteOf(state, state.selectedText))
                state = ReaderTransitions.selectionCleared(state)
            },
            onCopySelection = {
                Sharing.copy(context, QuireStrings.SHARE, state.selectedText.trim())
                state = ReaderTransitions.selectionCleared(state)
            },
        )

        // A brief confirmation, as the handoff shows, rather than a permanent badge.
        if (bookmarked) {
            BookmarkToast(onDone = { bookmarked = false })
        }

        when (state.overlay) {
            ReaderOverlay.TYPOGRAPHY -> TypographySheet(
                preferences = state.preferences,
                theme = theme,
                onFontChange = { applyPreferences(state.preferences.copy(font = it)) },
                onSizeChange = {
                    applyPreferences(ReaderTransitions.fontSizeChanged(state, it))
                },
                onJustifyChange = { applyPreferences(state.preferences.copy(justify = it)) },
                onThemeChange = onThemeChange,
                onDismiss = { state = ReaderTransitions.withOverlay(state, ReaderOverlay.NONE) },
            )

            ReaderOverlay.CONTENTS -> ContentsSheet(
                chapters = contents,
                currentIndex = state.chapterIndex,
                onSelect = { index ->
                    state = ReaderTransitions.withOverlay(state, ReaderOverlay.NONE)
                    scope.launch { loadChapter(index, null); persistNow() }
                },
                onDismiss = { state = ReaderTransitions.withOverlay(state, ReaderOverlay.NONE) },
            )

            else -> Unit
        }
    }
}

/**
 * A passage, as the share sheet wants it.
 *
 * One function for both routes — a chosen selection and the page under it — so the
 * card names the book, the author and the chapter the same way whichever the reader
 * used to get there.
 */
private fun quoteOf(state: ReaderState, text: String) = ShareCard.Quote(
    bookId = state.bookId,
    bookTitle = state.bookTitle,
    author = state.bookAuthor,
    text = text.trim(),
    // A chapter whose title is just its number reads as a stray digit on a card.
    // Books number their chapters and Quire detects that title faithfully; it is the
    // card that has to say what the number means.
    chapterLabel = state.chapterTitle
        ?.takeIf { it.isNotBlank() && !it.trim().all { c -> c.isDigit() } }
        ?: "Chapter ${state.chapterIndex + 1}",
    // The card is set in the face the reader is reading in. This is the only route to
    // the share sheet that has an answer; the others take the default.
    font = state.preferences.font,
)
