package app.folio.android.ui.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.folio.android.data.BookRepository
import app.folio.android.data.HabitRepository
import app.folio.android.data.HabitSummary
import app.folio.android.ui.FolioStrings
import app.folio.android.ui.common.EmptyState
import app.folio.android.ui.common.ErrorState
import app.folio.android.ui.details.BookDetailsScreen
import app.folio.android.ui.details.BookDetailsState
import app.folio.android.ui.importing.AddBookSheet
import app.folio.android.ui.importing.ImportProgressScreen
import app.folio.android.ui.library.LibraryScreen
import app.folio.android.ui.bookmarks.BookmarksScreen
import app.folio.android.ui.habit.LevelScreen
import app.folio.android.ui.habit.MilestonesScreen
import app.folio.android.ui.habit.BookCompleteScreen
import app.folio.android.ui.habit.GoalCompleteScreen
import app.folio.android.ui.habit.GoalScreen
import app.folio.android.ui.habit.OnboardingScreen
import app.folio.android.ui.habit.StreakScreen
import app.folio.android.ui.settings.SettingsScreen
import app.folio.android.share.ShareIntents
import app.folio.android.share.Sharing
import app.folio.android.share.SupportLink
import app.folio.android.ui.share.ShareCard
import app.folio.android.ui.share.ShareSheet
import app.folio.android.data.AppSettingsEntity
import app.folio.android.ui.library.LibraryState
import app.folio.android.ui.reader.PdfFallbackScreen
import app.folio.android.ui.reader.ReaderHost
import app.folio.android.ui.theme.FolioTheme
import app.folio.android.ui.theme.FolioThemeName
import app.folio.android.work.ImportProgress
import app.folio.core.model.PagePosition
import app.folio.core.model.ReadingPosition
import app.folio.core.model.FailureReason
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.map
import java.util.Calendar

/**
 * The top-level screen switch.
 *
 * A `when` over a small enum rather than a navigation library: three destinations,
 * no deep links yet, and a graph would be more machinery than the problem has.
 */
@Composable
fun FolioRoot(
    repository: BookRepository,
    habitRepository: HabitRepository,
    importProgress: ImportProgress?,
    theme: FolioThemeName = FolioThemeName.PAPER,
    onThemeChange: (FolioThemeName) -> Unit = {},
    onChooseFile: () -> Unit,
    onDismissImport: () -> Unit,
) {
    var destination by remember { mutableStateOf(FolioDestination.LIBRARY) }
    var showAddSheet by remember { mutableStateOf(false) }
    var openBookId by remember { mutableStateOf<String?>(null) }
    var readingBookId by remember { mutableStateOf<String?>(null) }
    var originalPdf by remember { mutableStateOf<java.io.File?>(null) }
    var scanPosition by remember { mutableStateOf(ReadingPosition(0, 0, 0)) }
    var scanPages by remember { mutableStateOf(0) }

    /** A scan's progress is how far through its pages the reader has come. */
    fun scanProgress(page: Int): Double =
        if (scanPages <= 1) 0.0 else (page.toDouble() / (scanPages - 1)).coerceIn(0.0, 1.0)
    var details by remember { mutableStateOf(BookDetailsState()) }

    LaunchedEffect(openBookId) {
        val id = openBookId
        details = if (id == null) BookDetailsState() else loadDetails(repository, id)
    }

    val state by remember(repository) {
        repository.observeLibrary().map { LibraryState(books = it, loading = false) }
    }.collectAsState(initial = LibraryState())

    val bookmarks by remember(repository) { repository.observeAllBookmarks() }
        .collectAsState(initial = emptyList())

    val habits by remember(habitRepository) { habitRepository.observeSummary() }
        .collectAsState(initial = HabitSummary())

    var habitScreen by remember { mutableStateOf<HabitScreen?>(null) }
    var shareCard by remember { mutableStateOf<ShareCard?>(null) }
    var goalJustReached by remember { mutableStateOf(false) }
    var onboardingSeen by remember { mutableStateOf(false) }
    var pendingGoal by remember { mutableStateOf(HabitRepository.RECOMMENDED_GOAL) }

    val settings by remember(habitRepository) { habitRepository.observeSettings() }
        .collectAsState(initial = AppSettingsEntity())

    var confirmExit by remember { mutableStateOf(false) }
    val activity = LocalActivity.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // One Back rule for the whole app, and it lives in [back] where a test can read
    // it. Every screen below is a `when` branch over these same variables, so Back
    // is that `when` in reverse rather than a second opinion about it.
    BackHandler(enabled = !confirmExit) {
        val here = NavSnapshot(
            readingOriginal = originalPdf != null,
            readingBookId = readingBookId,
            openBookId = openBookId,
            habitScreen = habitScreen,
            destination = destination,
        )
        when (val action = back(here)) {
            is BackAction.Pop -> {
                val next = action.next
                if (!next.readingOriginal) originalPdf = null
                readingBookId = next.readingBookId
                openBookId = next.openBookId
                habitScreen = next.habitScreen
                destination = next.destination
            }

            BackAction.ConfirmExit -> confirmExit = true
        }
    }

    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val scope = rememberCoroutineScope()

    FolioTheme(theme) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {

            val failure = importProgress?.failureReason?.let(::failureReasonOf)

            when {
                // First run, gated on a stored flag so it never reappears.
                !settings.onboarded && !onboardingSeen -> OnboardingScreen(
                    onGetStarted = { onboardingSeen = true },
                    modifier = Modifier.fillMaxSize(),
                )

                !settings.onboarded -> GoalScreen(
                    selected = pendingGoal,
                    onSelect = { pendingGoal = it },
                    onContinue = { scope.launch { habitRepository.setDailyGoal(pendingGoal) } },
                    modifier = Modifier.fillMaxSize(),
                )

                goalJustReached -> GoalCompleteScreen(
                    goalMinutes = habits.goalMinutes,
                    onContinue = { goalJustReached = false },
                    modifier = Modifier.fillMaxSize(),
                )
                // An import in flight owns the screen: the handoff shows it as a
                // full view, not a banner over the Library.
                failure != null -> ErrorState(
                    reason = failure,
                    onTryAnother = { onDismissImport(); showAddSheet = true },
                    modifier = Modifier.fillMaxSize(),
                )

                importProgress != null -> ImportProgressScreen(
                    stage = importProgress.stage,
                    pagesDone = importProgress.pagesDone,
                    pagesTotal = importProgress.pagesTotal,
                    onReadNow = onDismissImport,
                    modifier = Modifier.fillMaxSize(),
                )

                originalPdf != null -> PdfFallbackScreen(
                    file = originalPdf!!,
                    title = details.title,
                    onBack = { originalPdf = null },
                    modifier = Modifier.fillMaxSize(),
                    // A scan is read by page, so that is what its position is. The
                    // same storage as every other book, holding the one unit this
                    // one divides into.
                    initialPage = PagePosition.pageOf(scanPosition),
                    onPageChanged = { page ->
                        scope.launch {
                            repository.saveProgress(
                                details.id,
                                PagePosition.of(page),
                                progress = scanProgress(page),
                            )
                        }
                    },
                )

                readingBookId != null -> ReaderHost(
                    repository = repository,
                    habitRepository = habitRepository,
                    bookId = readingBookId!!,
                    onGoalReached = { goalJustReached = true },
                    theme = theme,
                    onThemeChange = onThemeChange,
                    onExit = { readingBookId = null },
                    modifier = Modifier.fillMaxSize(),
                )

                openBookId != null && !details.loading -> BookDetailsScreen(
                    state = details,
                    onBack = { openBookId = null },
                    onContinue = { readingBookId = details.id },
                    onReadOriginal = {
                        scope.launch {
                            scanPosition = repository.progressOf(details.id)
                            originalPdf = repository.originalFileOf(details.id)
                        }
                    },
                    onOpenContents = { /* Contents sheet arrives in Plan 4 */ },
                    onOpenBookmarks = { openBookId = null; destination = FolioDestination.BOOKMARKS },
                    onShare = {
                        shareCard = ShareCard.Quote(
                            bookId = details.id,
                            bookTitle = details.title,
                            author = details.author,
                            // The book, not a passage from it: Book Details is about
                            // the whole thing, and quoting its first paragraph here
                            // would be Folio choosing words the reader did not.
                            text = details.description.orEmpty(),
                            chapterLabel = "",
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                habitScreen == HabitScreen.STREAK -> StreakScreen(
                    summary = habits,
                    onContinue = { habitScreen = null },
                    onOpenMilestones = { habitScreen = HabitScreen.MILESTONES },
                    onOpenLevel = { habitScreen = HabitScreen.LEVEL },
                    onShare = {
                        shareCard = ShareCard.Streak(
                            days = habits.currentStreak,
                            week = habits.week(),
                            goalMinutes = habits.goalMinutes,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                habitScreen == HabitScreen.MILESTONES -> MilestonesScreen(
                    summary = habits,
                    onBack = { habitScreen = HabitScreen.STREAK },
                    modifier = Modifier.fillMaxSize(),
                )

                habitScreen == HabitScreen.LEVEL -> LevelScreen(
                    summary = habits,
                    onBack = { habitScreen = HabitScreen.STREAK },
                    modifier = Modifier.fillMaxSize(),
                )

                destination == FolioDestination.LIBRARY -> LibraryScreen(
                    state = state,
                    habits = habits,
                    hourOfDay = hour,
                    onOpenBook = { openBookId = it },
                    onAddBook = { showAddSheet = true },
                    onOpenStreak = { habitScreen = HabitScreen.STREAK },
                )

                destination == FolioDestination.BOOKMARKS -> BookmarksScreen(
                    bookmarks = bookmarks,
                    onOpen = { id, _ -> readingBookId = id },
                    onShare = { entry ->
                        shareCard = ShareCard.Quote(
                            bookId = entry.bookmark.bookId,
                            bookTitle = entry.bookTitle,
                            author = null,
                            text = entry.bookmark.snippet,
                            chapterLabel = "Chapter ${entry.bookmark.chapterIndex + 1}",
                        )
                    },
                    onRemove = { id -> scope.launch { repository.removeBookmark(id) } },
                    modifier = Modifier.fillMaxSize(),
                )

                else -> SettingsScreen(
                    settings = settings,
                    theme = theme,
                    bookCount = state.books.size,
                    onCycleTheme = {
                        val all = FolioThemeName.entries
                        onThemeChange(all[(all.indexOf(theme) + 1).mod(all.size)])
                    },
                    onGoalChange = { scope.launch { habitRepository.setDailyGoal(it) } },
                    onOpenLicences = { /* the licence text ships in res/raw */ },
                    onShowSupport = {
                        Sharing.start(context, ShareIntents.view(SupportLink.URL))
                    },
                    // Every external address goes out the same door: an ACTION_VIEW
                    // handed to whatever the reader uses. Folio has no INTERNET
                    // permission and fetches none of these itself.
                    onOpenLink = { url ->
                        Sharing.start(context, ShareIntents.view(url))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // The pill stays out of the way while a book is being prepared, and
            // while a book's own page is open.
            if (importProgress == null && openBookId == null && readingBookId == null &&
                habitScreen == null && settings.onboarded && !goalJustReached
            ) {
                FolioPillNav(
                    current = destination,
                    onSelect = { destination = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 18.dp),
                )
            }

            shareCard?.let { card ->
                ShareSheet(card = card, onDismiss = { shareCard = null })
            }

            if (confirmExit) {
                // Painted from the Folio palette, not Material's defaults. A dialog
                // that inherits the platform's lavender is the one surface in the app
                // that ignores the theme the reader chose, and it shows.
                val colors = app.folio.android.ui.theme.Folio.colors
                AlertDialog(
                    onDismissRequest = { confirmExit = false },
                    containerColor = colors.bgAlt,
                    titleContentColor = colors.ink,
                    textContentColor = colors.muted,
                    shape = app.folio.android.ui.theme.FolioShapes.card,
                    title = { Text(FolioStrings.CLOSE_FOLIO) },
                    text = { Text(FolioStrings.CLOSE_FOLIO_HINT) },
                    confirmButton = {
                        TextButton(onClick = { activity?.finish() }) {
                            Text(FolioStrings.CLOSE, color = colors.accent)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmExit = false }) {
                            Text(FolioStrings.KEEP_READING, color = colors.muted)
                        }
                    },
                )
            }

            if (showAddSheet) {
                AddBookSheet(
                    onChooseFile = { showAddSheet = false; onChooseFile() },
                    onDismiss = { showAddSheet = false },
                )
            }
        }
    }
}

/**
 * Reads what Book Details needs for one book.
 *
 * Only the current chapter is loaded from disk. Reading every chapter to fill in a
 * subtitle would defeat the point of storing them separately.
 */
private suspend fun loadDetails(
    repository: BookRepository,
    bookId: String,
): BookDetailsState {
    val entity = repository.find(bookId) ?: return BookDetailsState(loading = false)
    val position = repository.progressOf(bookId)
    val chapterTitle = repository.loadChapter(bookId, position.chapterIndex)?.title
    return BookDetailsState.from(
        entity = entity,
        progress = repository.storedProgress(bookId) ?: 0.0,
        chapterIndex = position.chapterIndex,
        chapterTitle = chapterTitle,
        dailyGoalMinutes = 20,
    )
}

/**
 * Turns the worker's failure string back into a reason.
 *
 * WorkManager output data is strings, so this crosses a stringly-typed boundary.
 * An unrecognised value maps to a generic failure rather than crashing — a reason
 * added later should degrade to a vague message, not an exception.
 */
private fun failureReasonOf(name: String): FailureReason =
    FailureReason.entries.firstOrNull { it.name == name } ?: FailureReason.EXTRACTION_FAILED

/** The habit screens, which sit above the tab destinations rather than beside them. */
enum class HabitScreen { STREAK, MILESTONES, LEVEL }
