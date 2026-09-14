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
import app.quire.core.paginate.Paginator
import app.quire.core.paginate.Viewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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

    /**
     * Whether the reader's saved typography has arrived from the database.
     *
     * Pagination waits for it. It used to race it: the viewport is reported as soon
     * as the page is laid out, while the settings are a suspending read, so a long
     * chapter could be laid out at the default 19sp serif and then drawn at the saved
     * 22sp Lora — pages measured for one size, rendered at another, and nothing to
     * repaginate them until the reader touched the type stepper themselves. Waiting
     * costs one database read; getting it wrong costs the whole chapter's layout.
     */
    var typographyLoaded by remember(bookId) { mutableStateOf(false) }

    val composeMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontResolver = LocalFontFamilyResolver.current
    val scope = rememberCoroutineScope()
    val tracker = remember(bookId) { SessionTracker(habitRepository) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val measurer = remember(state.preferences.font, density, fontResolver) {
        ComposeTextMeasurer(composeMeasurer, density, state.preferences.font.family())
    }
    // Kept across type-size changes, so the pages laid out at the size the reader
    // stepped away from are still there when they step back.
    val pageCache = remember(bookId) { PageCache() }
    val chapterPaginator = remember(measurer, pageCache) {
        ChapterPaginator(Paginator(measurer), pageCache)
    }

    // sp -> px and dp -> px for this screen. The paginator reasons in device pixels
    // because the viewport does.
    val pixelsPerSp = with(density) { 1.sp.toPx() }
    val pixelsPerDp = with(density) { 1.dp.toPx() }

    suspend fun pagesFor(chapter: Chapter, request: PaginationRequest): List<Page> =
        chapterPaginator.pagesFor(chapter, request)

    fun requestFor(chapter: Chapter, prefs: ReaderPreferences) =
        ReaderLayout.requestFor(chapter, viewport, prefs, pixelsPerSp, pixelsPerDp)

    suspend fun loadChapter(index: Int, at: ReadingPosition?) {
        val entity = repository.find(bookId) ?: return
        val chapter: Chapter = repository.loadChapter(bookId, index) ?: return
        // The request is built from the chapter just loaded, not from the one being
        // left. Built from the outgoing state, the header inset was decided by the
        // previous chapter at the reader's previous page index — nearly always zero —
        // while the renderer drew a header the pagination had made no room for.
        val pages = pagesFor(chapter, requestFor(chapter, state.preferences))
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

    /**
     * The one place a chapter is paginated.
     *
     * Keyed on everything page breaks depend on, so Compose restarts it exactly when
     * they change and cancels the run it replaces. There were two paths before — this
     * effect and `applyPreferences` — which neither cancelled each other nor agreed
     * on the cache key, so a reader stepping the type size left several paginations
     * of the same long chapter running at once, racing to write the state. Whichever
     * finished last won, whatever size it had been asked for. That is the type
     * control doing nothing for a few seconds and then settling.
     *
     * Gated on the viewport being real — pages laid out against a zero-sized box are
     * thrown away immediately — and on the saved typography having arrived.
     */
    val layoutSettings = state.preferences.toSettings(pixelsPerSp)
    LaunchedEffect(bookId, viewport, layoutSettings, typographyLoaded) {
        when (val step = ReaderLayout.stepFor(state, viewport, typographyLoaded)) {
            PaginationStep.Wait -> Unit

            PaginationStep.Open -> {
                val saved = repository.progressOf(bookId)
                loadChapter(saved.chapterIndex, saved)
                tracker.record()
            }

            is PaginationStep.Repaginate -> {
                val pages = pagesFor(step.chapter, requestFor(step.chapter, state.preferences))
                // Named, so `repaginated` can refuse pages laid out for a chapter the
                // reader has since left: the Contents sheet loads one in a coroutine
                // this effect does not cancel.
                state = ReaderTransitions.repaginated(
                    state, step.chapter, pages, state.preferences,
                )
            }
        }
    }

    suspend fun persistNow() {
        repository.saveProgress(bookId, state.position, state.progress)
    }

    /**
     * Records a typography change and lets the one pagination effect react to it.
     *
     * It used to paginate here as well, in a coroutine of its own that nothing
     * cancelled. Four taps on the size stepper launched four layouts of the same long
     * chapter, all live at once, each writing the state when it finished — so the
     * size that stuck was whichever finished last, not whichever was asked for last.
     * Setting the preference and letting the keyed effect restart gives cancellation
     * of the superseded run for free, and leaves one place that knows how a chapter
     * is laid out.
     */
    fun applyPreferences(next: ReaderPreferences) {
        state = state.copy(preferences = next)
        scope.launch {
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
        // Nothing paginated yet. Remember the turn rather than dropping it: with no
        // pages, "next page" reports that this is the last one, which the chapter
        // boundary branch below then abandoned because the chapter count was not
        // loaded either. On a book large enough to take seconds, that was every tap
        // the reader made until pagination finished, silently doing nothing.
        if (state.pages.isEmpty()) {
            state = ReaderTransitions.queuedTurn(state, forward)
            tracker.record()
            return
        }

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
    // 22pt should not have to choose it again next time they open a book. Pagination
    // waits on the flag this sets — see [typographyLoaded].
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
        typographyLoaded = true
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
            // A tap on the chosen words keeps them; a tap anywhere else lets go.
            // Clearing on every tap is how a reader lost a passage they had just
            // spent two handle drags getting right.
            onSelectionTap = { state = ReaderTransitions.tappedWhileSelecting(state, it) },
            onHandleGrab = { state = ReaderTransitions.handleGrabbed(state, it) },
            onHandleMove = { state = ReaderTransitions.handleMoved(state, it) },
            onHandleRelease = { state = ReaderTransitions.handleReleased(state) },
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
        // state.chapterLabel, not a locally rebuilt "Chapter N": the header and the
        // share card should not each have their own opinion of what a chapter is
        // called.
        ?: state.chapterLabel,
    // The card is set in the face the reader is reading in. This is the only route to
    // the share sheet that has an answer; the others take the default.
    font = state.preferences.font,
)
