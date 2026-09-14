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
import app.quire.core.paginate.PageWindow
import app.quire.core.paginate.Paginator
import app.quire.core.paginate.Viewport
import app.quire.core.reading.TextAnchor
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

    /**
     * Whether a window is being built right now — grown, re-anchored, or loaded with
     * the chapter it belongs to.
     *
     * A tap made while one is in flight is remembered rather than launching a second
     * run: two runs started from the same window compute the same answer, so the
     * second tap would move the reader nowhere. Queued, two taps move them two pages.
     */
    var windowInFlight by remember(bookId) { mutableStateOf(false) }

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

    /**
     * Lays out one run of a chapter: the primitive [ReaderWindow] builds windows from.
     *
     * A lambda rather than a method so `ReaderWindow` knows nothing about caches,
     * dispatchers or device pixels — which is what lets every rule about where a
     * window starts and when it grows be tested on a JVM.
     */
    fun layFor(
        chapter: Chapter,
        prefs: ReaderPreferences,
        /**
         * Captured, not read per call. A single window is laid out in up to five
         * runs; a rotation between two of them would concatenate pages set in two
         * different columns into one list, and the reader's place is then resolved
         * against breaks that belong to neither.
         */
        view: Viewport,
    ): suspend (TextAnchor, Int) -> PageWindow =
        { from, maxPages ->
            chapterPaginator.windowFor(
                chapter,
                // The request is built from the chapter just loaded, not from the one
                // being left. Built from the outgoing state, the header inset was
                // decided by the previous chapter at the reader's previous page index
                // — nearly always zero — while the renderer drew a header the
                // pagination had made no room for.
                ReaderLayout.requestFor(
                    chapter, from, maxPages, view, prefs, pixelsPerSp, pixelsPerDp,
                ),
            )
        }

    fun charsBehind(prefs: ReaderPreferences, view: Viewport) =
        ReaderWindow.charsBehind(view, prefs.toSettings(pixelsPerSp))

    /**
     * @param atEnd open at the chapter's final character rather than at [at].
     *   Entering a chapter backwards has to land on its last page, and with a window
     *   that page does not exist until something asks for it — there is no page list
     *   to index to the end of. Resolved here rather than by the caller so the chapter
     *   is read from disk once; on a book whose only chapter is 8,621 blocks, reading
     *   it twice to work out where it ends is not a rounding error.
     */
    suspend fun loadChapter(index: Int, at: ReadingPosition?, atEnd: Boolean = false) {
        // Read before the suspending work, and used for the lay-out *and* the key it
        // is stamped with. Read again afterwards, a reader who stepped the type size
        // while the chapter loaded would leave the state claiming pages match a
        // typography they were never laid out for — which is the one thing LayoutKey
        // exists to make impossible.
        val prefs = state.preferences
        val view = viewport
        val entity = repository.find(bookId) ?: return
        val chapter: Chapter = repository.loadChapter(bookId, index) ?: return
        val opensAt = if (!atEnd) at else chapter.cursorAt(chapter.textLength)
            .let { ReadingPosition(index, it.blockIndex, it.charOffset) }
        val window = ReaderWindow.openAt(
            chapter, opensAt, charsBehind(prefs, view), layFor(chapter, prefs, view),
        )
        state = ReaderTransitions.openedChapter(
            state.copy(
                bookId = bookId,
                bookTitle = entity.title,
                bookAuthor = entity.author,
                chapterCount = entity.chapterCount,
                bookTotalChars = entity.totalChars,
            ),
            chapter, window, opensAt, LayoutKey(view, prefs.toSettings(pixelsPerSp)),
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
                // Read once, and used for the lay-out and for the key it is stamped
                // with. A run that finishes before its cancellation lands would
                // otherwise claim a viewport its pages were not set in.
                val view = viewport
                // From the window's own start, not from a cursor worked out afresh
                // around the reader: a start that moved on every tap of the stepper
                // would re-tile the pages under them each time.
                val window = ReaderWindow.relaidOut(
                    step.chapter, state.window,
                    layFor(step.chapter, state.preferences, view),
                )
                // Named, so `repaginated` can refuse pages laid out for a chapter the
                // reader has since left: the Contents sheet loads one in a coroutine
                // this effect does not cancel.
                state = ReaderTransitions.repaginated(
                    state, step.chapter, window, state.preferences,
                    LayoutKey(view, layoutSettings),
                )
            }
        }
    }

    /**
     * Lays out more of the chapter before the reader reaches the end of what is.
     *
     * A prefetch, and forward only. An extension that happened on the page turn
     * itself would be a stutter at every seam, which is exactly the thing a reader
     * would notice — and the backward equivalent is deliberately *not* here, because
     * it re-tiles and would move the page under a reader who had not asked for it.
     *
     * Keyed on the carry as well as on wanting more, so it runs again after each
     * extension rather than once, and so Compose cancels a run whose chapter,
     * viewport or typography has been replaced.
     */
    val layoutKey = LayoutKey(viewport, layoutSettings)
    // Only ever asked of a window that was measured against what is on screen now.
    // Extending one that was not appends pages laid out at the new type size to pages
    // laid out at the old, and the reader is then on a page list that half of the app
    // disagrees with — the exact shape of fault the single keyed effect was introduced
    // to remove. The repagination above puts this right within one run, and the effect
    // restarts when it does.
    val wantsMore = ReaderLayout.mayGrowWindow(state, viewport, layoutSettings) &&
        ReaderWindow.wantsForwardExtension(state.window)
    LaunchedEffect(bookId, state.chapterIndex, state.windowNext, wantsMore, layoutKey) {
        if (!wantsMore || !typographyLoaded) return@LaunchedEffect
        val chapter = state.chapter ?: return@LaunchedEffect
        val from = state.windowNext ?: return@LaunchedEffect
        val more = ReaderWindow.extensionFor(
            state.window, layFor(chapter, state.preferences, viewport),
        ) ?: return@LaunchedEffect
        // Appended to the window as it is *now*, not as it was when the lay-out
        // started. The reader is three pages from the edge and reading, so they turn
        // pages while this runs; writing back the window captured before it would put
        // them silently back where they were, and the turn would be gone.
        val now = state.window
        if (now.next == from && ReaderLayout.mayGrowWindow(state, viewport, layoutSettings)) {
            state = ReaderTransitions.windowed(
                state, chapter, ReaderWindow.appended(now, more), layoutKey,
            )
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

        // The end of the *window* is not the end of the chapter. Asking `atLastPage`
        // alone would drop the reader into the next chapter every twelve pages of a
        // book that has only one.
        val chapter = state.chapter
        if (chapter != null && !(if (forward) state.atChapterEnd else state.atChapterStart)) {
            // Two reasons to remember the tap rather than act on it, and neither may
            // swallow it — `windowed` and `repaginated` both apply what is queued.
            //
            // - The pages in hand are about to be replaced by a repagination, so
            //   growing them would mix two type sizes in one page list.
            // - A run is already in flight. A second tap would start a second run from
            //   the *same* window, which computes the same answer — so two taps would
            //   move the reader one page. Queued, they move them two.
            if (windowInFlight || !ReaderLayout.mayGrowWindow(state, viewport, layoutSettings)) {
                state = ReaderTransitions.queuedTurn(state, forward)
                tracker.record()
                return
            }
            // Read once and used throughout: the reader can step the type size or
            // rotate the phone while this runs, and laying out against one viewport
            // and stamping the result with another is how a window comes to claim
            // pages it does not have.
            val prefs = state.preferences
            val view = viewport
            val key = LayoutKey(view, layoutSettings)
            windowInFlight = true
            scope.launch {
                try {
                    val base = state.window
                    if (forward) {
                        // Normally already done by the prefetch effect; this is the
                        // reader outrunning it. Appended to the window as it is when
                        // the lay-out finishes, for the same reason the prefetch does.
                        val more = ReaderWindow.extensionFor(base, layFor(chapter, prefs, view))
                        val now = state.window
                        // The current window unchanged when something else grew it
                        // first, rather than skipping the transition: `windowed` is
                        // also where taps queued during this run are applied, and
                        // leaving them queued would spring them on the reader at the
                        // next window event instead.
                        val grown =
                            if (more != null && now.next == base.next) {
                                ReaderWindow.appended(now, more)
                            } else {
                                now
                            }
                        state = ReaderTransitions.windowed(state, chapter, grown, key)
                        // The step the reader asked for, taken against whatever the
                        // window is now — so a tap is still honoured when something
                        // else grew it first.
                        ReaderTransitions.nextPage(state)?.let { state = it }
                    } else {
                        val back = ReaderWindow.turnedBack(
                            chapter, base, charsBehind(prefs, view), layFor(chapter, prefs, view),
                        )
                        // A re-anchor cannot be rebased the way an append can: the page
                        // it chose was chosen against the window it started from. If
                        // the reader moved meanwhile, it is dropped rather than written
                        // back over them — turning forward during a re-anchor used to
                        // drag them backwards past where they had just gone.
                        if (ReaderLayout.mayAdoptReanchor(state, base)) {
                            state = ReaderTransitions.windowed(state, chapter, back, key)
                        }
                    }
                    // Once, on every path out: the run may have been dropped — the
                    // carry moved under it, or the reader did — and a turn left queued
                    // would be cashed by the next background extension instead, which
                    // is the page moving with nothing touching the screen.
                    state = ReaderTransitions.pendingTurnsApplied(state)
                } finally {
                    windowInFlight = false
                }
                tracker.record()
                persist()
            }
            return
        }

        // Crossing a chapter boundary.
        val target = if (forward) state.chapterIndex + 1 else state.chapterIndex - 1
        if (target < 0 || target >= state.chapterCount) return
        // Guarded like the branch above, and for the same reason: two rapid taps at a
        // boundary launched two loads of the same chapter, which compute the same
        // answer, so two taps moved the reader one chapter and the second load
        // overwrote the first.
        if (windowInFlight) return
        windowInFlight = true
        scope.launch {
            try {
                // Entering a chapter backwards lands on its last page, not its first —
                // otherwise turning back skips the whole chapter.
                loadChapter(target, at = null, atEnd = !forward)
            } finally {
                windowInFlight = false
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
