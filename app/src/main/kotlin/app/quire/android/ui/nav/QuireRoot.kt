package app.quire.android.ui.nav

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
import app.quire.android.data.BookRepository
import app.quire.android.data.HabitRepository
import app.quire.android.data.HabitSummary
import app.quire.android.ui.QuireStrings
import app.quire.android.ui.common.EmptyState
import app.quire.android.ui.common.ErrorState
import app.quire.android.ui.details.BookDetailsScreen
import app.quire.android.ui.details.BookDetailsState
import app.quire.android.ui.importing.AddBookSheet
import app.quire.android.ui.importing.ImportProgressScreen
import app.quire.android.ui.library.LibraryScreen
import app.quire.android.ui.bookmarks.BookmarksScreen
import app.quire.android.ui.habit.LevelScreen
import app.quire.android.ui.habit.MilestonesScreen
import app.quire.android.ui.habit.BookCompleteScreen
import app.quire.android.ui.habit.GoalCompleteScreen
import app.quire.android.ui.habit.GoalScreen
import app.quire.android.ui.habit.StreakScreen
import app.quire.android.ui.onboarding.OnboardingGuide
import app.quire.android.ui.onboarding.OnboardingGuideScreen
import app.quire.android.ui.notify.ReminderInviteScreen
import app.quire.android.ui.settings.SettingsScreen
import app.quire.android.notify.ReminderPermission
import app.quire.android.notify.Reminders
import android.text.format.DateFormat
import app.quire.android.share.ShareIntents
import app.quire.android.share.Sharing
import app.quire.android.share.SupportLink
import app.quire.android.ui.share.ShareCard
import app.quire.android.ui.share.ShareSheet
import app.quire.android.data.AppSettingsEntity
import app.quire.android.ui.library.LibraryState
import app.quire.android.ui.reader.PdfFallbackScreen
import app.quire.android.ui.reader.ReaderHost
import app.quire.android.ui.theme.QuireTheme
import app.quire.android.ui.theme.QuireThemeName
import app.quire.android.work.ImportProgress
import app.quire.core.model.PagePosition
import app.quire.core.model.ReadingPosition
import app.quire.core.model.FailureReason
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
fun QuireRoot(
    repository: BookRepository,
    habitRepository: HabitRepository,
    importProgress: ImportProgress?,
    theme: QuireThemeName = QuireThemeName.PAPER,
    onThemeChange: (QuireThemeName) -> Unit = {},
    onChooseFile: () -> Unit,
    onDismissImport: () -> Unit,
    /** A destination a home-screen widget asked for, not yet taken. */
    pendingHabitScreen: HabitScreen? = null,
    onHabitScreenOpened: () -> Unit = {},
    /**
     * Turns reminders on, raising the system prompt if that is what is needed.
     *
     * Supplied by the Activity because a runtime permission needs an
     * `ActivityResultLauncher`, which only an Activity can register.
     */
    onEnableReminders: () -> Unit = {},
    onDisableReminders: () -> Unit = {},
    /** Re-enqueues the pending job so a changed time takes effect tonight. */
    onRescheduleReminders: () -> Unit = {},
    /** Opens Quire's own page in system notification settings. */
    onOpenNotificationSettings: () -> Unit = {},
    /** Whether the OS will currently deliver anything Quire posts. */
    canPostNotifications: Boolean = true,
) {
    var destination by remember { mutableStateOf(QuireDestination.LIBRARY) }
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

    // A widget tap. Taken once and handed back, rather than treated as a start
    // destination: the reader can leave the streak screen and tap the widget again,
    // and a value that never changed would not bring them back the second time.
    LaunchedEffect(pendingHabitScreen) {
        pendingHabitScreen?.let {
            destination = QuireDestination.LIBRARY
            openBookId = null
            readingBookId = null
            habitScreen = it
            onHabitScreenOpened()
        }
    }

    var shareCard by remember { mutableStateOf<ShareCard?>(null) }
    var goalJustReached by remember { mutableStateOf(false) }
    var offerReminders by remember { mutableStateOf(false) }
    // Set the moment the reader leaves the guide, so the last tap does not leave it
    // on screen for the frame or two before the stored flag comes back.
    var onboardingSeen by remember { mutableStateOf(false) }
    var guidePage by remember { mutableStateOf(0) }
    var pendingGoal by remember { mutableStateOf(HabitRepository.RECOMMENDED_GOAL) }

    // Null until the database answers, deliberately. A default `AppSettingsEntity()`
    // has `onboarded = false`, so every cold start rendered the onboarding screen for
    // the frame or two before the real row arrived — a reader who has used Quire for
    // months was greeted with "A quiet place to read / Get Started" each time they
    // opened it. The honest state before the answer is "not known yet", and the
    // screen for that is the empty page below rather than a guess.
    val storedSettings by remember(habitRepository) { habitRepository.observeSettings() }
        .collectAsState(initial = null)
    val settings = storedSettings ?: AppSettingsEntity()

    var confirmExit by remember { mutableStateOf(false) }
    var confirmRemoveBook by remember { mutableStateOf<String?>(null) }
    val activity = LocalActivity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // Whether the invitation is the screen in front of the reader, rather than
    // merely pending. The rule is in [invitationVisible] where a test can read it,
    // and it is used by both the screen switch below and the Back handler.
    val invitePending = invitationVisible(
        offered = offerReminders,
        onboarded = settings.onboarded,
        readingBookId = readingBookId,
        readingOriginal = originalPdf != null,
        importing = importProgress != null,
        importFailed = importProgress?.failureReason != null,
        goalJustReached = goalJustReached,
    )

    // One Back rule for the whole app, and it lives in [back] where a test can read
    // it. Every screen below is a `when` branch over these same variables, so Back
    // is that `when` in reverse rather than a second opinion about it.
    BackHandler(enabled = !confirmExit) {
        if (invitePending) {
            // Backing out of the offer is a "no thanks" like any other, and it is
            // recorded as one. Leaving the flag unset would bring the question back
            // the next time a session recorded minutes, which is the definition of
            // nagging about not being allowed to nag.
            offerReminders = false
            scope.launch { habitRepository.markRemindersAsked() }
            return@BackHandler
        }
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

    QuireTheme(theme) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {

            val failure = importProgress?.failureReason?.let(::failureReasonOf)

            when {
                // Nothing at all until the settings row has been read. One blank
                // frame on the theme's own ground is invisible; guessing wrong and
                // showing onboarding to an existing reader is not. The rule lives in
                // [OnboardingGuide.guideVisible] where a test can reach it, and the
                // null check below is the same one stated twice on purpose.
                storedSettings == null -> Unit

                // The opening guide, once, on the first launch after an install.
                // `guideSeen` is written the moment the reader leaves it — read or
                // skipped — so closing Quire between here and the goal picker does
                // not bring it back.
                OnboardingGuide.guideVisible(storedSettings, onboardingSeen) ->
                    OnboardingGuideScreen(
                        index = guidePage,
                        onNext = {
                            when (val next = OnboardingGuide.next(guidePage)) {
                                null -> {
                                    onboardingSeen = true
                                    scope.launch { habitRepository.markGuideSeen() }
                                }

                                else -> guidePage = next
                            }
                        },
                        onSkip = {
                            onboardingSeen = true
                            scope.launch { habitRepository.markGuideSeen() }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                OnboardingGuide.goalVisible(storedSettings, onboardingSeen) -> GoalScreen(
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
                    onSessionRecorded = { minutes ->
                        if (ReminderPermission.shouldInvite(settings, minutes)) {
                            offerReminders = true
                        }
                    },
                    theme = theme,
                    onThemeChange = onThemeChange,
                    onExit = { readingBookId = null },
                    onShareQuote = { shareCard = it },
                    modifier = Modifier.fillMaxSize(),
                )

                // Below the Reader, deliberately. A session is also flushed when the
                // app goes to the background, so a reader who locks their phone
                // mid-chapter would otherwise come back to this question instead of
                // to their book. Here it waits until they have actually left.
                invitePending -> ReminderInviteScreen(
                    time = Reminders.formatTime(
                        settings.reminderMinuteOfDay,
                        use24Hour = DateFormat.is24HourFormat(context),
                    ),
                    onAccept = { offerReminders = false; onEnableReminders() },
                    onDecline = {
                        offerReminders = false
                        // Asked, and answered. The flag is set on a "no" exactly as
                        // it is on a yes, so declining is something the reader does
                        // once rather than something they keep having to do.
                        scope.launch { habitRepository.markRemindersAsked() }
                    },
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
                    onOpenBookmarks = { openBookId = null; destination = QuireDestination.BOOKMARKS },
                    onRemove = { confirmRemoveBook = details.id },
                    onShare = {
                        shareCard = ShareCard.Quote(
                            bookId = details.id,
                            bookTitle = details.title,
                            author = details.author,
                            // The book, not a passage from it: Book Details is about
                            // the whole thing, and quoting its first paragraph here
                            // would be Quire choosing words the reader did not.
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

                destination == QuireDestination.LIBRARY -> LibraryScreen(
                    state = state,
                    habits = habits,
                    hourOfDay = hour,
                    onOpenBook = { openBookId = it },
                    onAddBook = { showAddSheet = true },
                    onOpenStreak = { habitScreen = HabitScreen.STREAK },
                )

                destination == QuireDestination.BOOKMARKS -> BookmarksScreen(
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
                        val all = QuireThemeName.entries
                        onThemeChange(all[(all.indexOf(theme) + 1).mod(all.size)])
                    },
                    onGoalChange = { scope.launch { habitRepository.setDailyGoal(it) } },
                    canPostNotifications = canPostNotifications,
                    use24HourClock = DateFormat.is24HourFormat(context),
                    // Enabling routes through the Activity because it may need the
                    // system prompt; disabling cancels the pending job as well as
                    // clearing the flag, so off is off now rather than at the next
                    // scheduled wake-up.
                    onRemindersChange = { wanted ->
                        if (wanted) onEnableReminders() else onDisableReminders()
                    },
                    onReminderTimeChange = { minute ->
                        scope.launch {
                            habitRepository.setReminderTime(minute)
                            // The pending job carries the old delay, so the new time
                            // only means anything once the job is replaced. Without
                            // this the change would take effect a day late.
                            onRescheduleReminders()
                        }
                    },
                    onReminderKindsChange = { daily, streak ->
                        scope.launch { habitRepository.setReminderKinds(daily, streak) }
                    },
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onOpenLicences = { /* the licence text ships in res/raw */ },
                    onShowSupport = {
                        Sharing.start(context, ShareIntents.view(SupportLink.URL))
                    },
                    // Every external address goes out the same door: an ACTION_VIEW
                    // handed to whatever the reader uses. Quire has no INTERNET
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
                habitScreen == null && settings.onboarded && !goalJustReached &&
                !invitePending
            ) {
                QuirePillNav(
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

            confirmRemoveBook?.let { removingId ->
                // Asked, because it cannot be undone. The body says what actually
                // goes and what does not; "are you sure?" would tell the reader
                // nothing they did not already know.
                val colors = app.quire.android.ui.theme.Quire.colors
                AlertDialog(
                    onDismissRequest = { confirmRemoveBook = null },
                    containerColor = colors.bgAlt,
                    titleContentColor = colors.ink,
                    textContentColor = colors.muted,
                    shape = app.quire.android.ui.theme.QuireShapes.card,
                    title = { Text(QuireStrings.REMOVE_BOOK_TITLE) },
                    text = { Text(QuireStrings.REMOVE_BOOK_BODY) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmRemoveBook = null
                            // Leave the book's own page first: it is about to stop
                            // existing, and its details would be read from a row that
                            // is no longer there.
                            openBookId = null
                            scope.launch { repository.delete(removingId) }
                        }) {
                            Text(QuireStrings.REMOVE, color = colors.errorText)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmRemoveBook = null }) {
                            Text(QuireStrings.KEEP_READING, color = colors.muted)
                        }
                    },
                )
            }

            if (confirmExit) {
                // Painted from the Quire palette, not Material's defaults. A dialog
                // that inherits the platform's lavender is the one surface in the app
                // that ignores the theme the reader chose, and it shows.
                val colors = app.quire.android.ui.theme.Quire.colors
                AlertDialog(
                    onDismissRequest = { confirmExit = false },
                    containerColor = colors.bgAlt,
                    titleContentColor = colors.ink,
                    textContentColor = colors.muted,
                    shape = app.quire.android.ui.theme.QuireShapes.card,
                    title = { Text(QuireStrings.CLOSE_QUIRE) },
                    text = { Text(QuireStrings.CLOSE_QUIRE_HINT) },
                    confirmButton = {
                        TextButton(onClick = { activity?.finish() }) {
                            Text(QuireStrings.CLOSE, color = colors.accent)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmExit = false }) {
                            Text(QuireStrings.KEEP_READING, color = colors.muted)
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
