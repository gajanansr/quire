package app.folio.android.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import app.folio.android.data.BookRepository
import app.folio.android.ui.theme.FolioThemeName
import app.folio.android.ui.theme.ReaderFont
import app.folio.core.model.Chapter
import app.folio.core.model.ChapterRef
import app.folio.core.model.ReadingPosition
import app.folio.core.paginate.Paginator
import app.folio.core.paginate.Viewport
import kotlinx.coroutines.Dispatchers
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
    bookId: String,
    theme: FolioThemeName,
    onThemeChange: (FolioThemeName) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember(bookId) { mutableStateOf(ReaderState()) }
    var viewport by remember { mutableStateOf(Viewport(0f, 0f)) }
    var contents by remember(bookId) { mutableStateOf<List<ChapterRef>>(emptyList()) }

    val composeMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontResolver = LocalFontFamilyResolver.current
    val scope = rememberCoroutineScope()

    val measurer = remember(state.preferences.font, density, fontResolver) {
        ComposeTextMeasurer(composeMeasurer, density, state.preferences.font.family())
    }
    val paginator = remember(measurer) { Paginator(measurer) }

    // sp -> px for this screen. The paginator reasons in device pixels because the
    // viewport does.
    val pixelsPerSp = with(density) { 1.sp.toPx() }

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
        return with(density) { (body * 1.2f + body * 2.4f + 40.dp.toPx() / 1f) }
    }

    suspend fun loadChapter(index: Int, at: ReadingPosition?) {
        val entity = repository.find(bookId) ?: return
        val chapter: Chapter = repository.loadChapter(bookId, index) ?: return
        val pages = withContext(Dispatchers.Default) {
            paginator.paginate(chapter, viewport, state.preferences.toSettings(pixelsPerSp), headerInsetPx())
        }
        state = ReaderTransitions.openedChapter(
            state.copy(
                bookId = bookId,
                bookTitle = entity.title,
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
        } else {
            // The viewport changed — a rotation, or the first real measurement.
            val chapter = state.chapter ?: return@LaunchedEffect
            val pages = withContext(Dispatchers.Default) {
                paginator.paginate(
                    chapter, viewport, state.preferences.toSettings(pixelsPerSp), headerInsetPx(),
                )
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
            val pages = withContext(Dispatchers.Default) {
                Paginator(
                    ComposeTextMeasurer(composeMeasurer, density, next.font.family()),
                ).paginate(
                    chapter, viewport, next.toSettings(pixelsPerSp),
                    if (state.showsChapterHeader) headerInsetPx() else 0f,
                )
            }
            state = ReaderTransitions.repaginated(state, pages, next)
            persistNow()
        }
    }

    fun persist() {
        val snapshot = state
        scope.launch {
            repository.saveProgress(bookId, snapshot.position, snapshot.progress)
        }
    }

    fun turn(forward: Boolean) {
        val next = if (forward) ReaderTransitions.nextPage(state)
        else ReaderTransitions.previousPage(state)

        if (next != null) {
            state = next
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

    LaunchedEffect(bookId) { contents = repository.chapterIndex(bookId) }

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
            onBookmark = { /* Task 7 */ },
            onFinish = { persist(); onExit() },
        )

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
