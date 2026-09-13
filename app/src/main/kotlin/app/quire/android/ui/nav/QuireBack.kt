package app.quire.android.ui.nav

/**
 * Where the reader is standing, as far as Back is concerned.
 *
 * A snapshot of the few `QuireRoot` state variables that stack, rather than a
 * navigation library's back stack. `QuireRoot` switches on these with a `when`, and
 * Back is the same `when` read in reverse — so it is written once, here, where a test
 * can reach it without a device.
 */
data class NavSnapshot(
    /** The original PDF viewer, shown over the Reader for a scanned book. */
    val readingOriginal: Boolean = false,
    val readingBookId: String? = null,
    val openBookId: String? = null,
    val habitScreen: HabitScreen? = null,
    val destination: QuireDestination = QuireDestination.LIBRARY,
)

/**
 * Whether the reminder invitation is the screen actually in front of the reader.
 *
 * "The reader has earned the offer" and "the offer is on screen" are not the same
 * thing, and conflating them cost a real bug. A reading session is flushed when the
 * app goes to the background, so the offer can be raised while the reader is still
 * inside the Reader — and every screen above it keeps rendering. Back would then be
 * swallowed by a screen nobody could see, and the one-shot offer marked as answered
 * without ever having been shown: the reader loses a Back press *and* loses
 * reminders for good, with nothing on screen to explain either.
 *
 * Stated once, here, and read by both the screen switch and the Back handler, so the
 * two cannot drift into disagreeing about what is visible.
 */
fun invitationVisible(
    offered: Boolean,
    onboarded: Boolean,
    readingBookId: String?,
    readingOriginal: Boolean,
    importing: Boolean,
    importFailed: Boolean,
    goalJustReached: Boolean,
): Boolean = offered &&
    // One term per screen `QuireRoot` draws ahead of the invitation, including the
    // ones that cannot currently coincide with an offer: onboarding, because
    // `ReminderPermission.shouldInvite` requires it, and [importFailed], which today
    // is derived from [importing] and so is already covered by it. A predicate that
    // is complete *except* for the terms that happen to be redundant is exactly the
    // kind that drifts back out of step the next time the list changes — which is
    // how this function came to exist.
    onboarded &&
    readingBookId == null &&
    !readingOriginal &&
    !importing &&
    !importFailed &&
    !goalJustReached

/** What a press of Back should do from a given [NavSnapshot]. */
sealed interface BackAction {
    /** Go one level out, to this state. */
    data class Pop(val next: NavSnapshot) : BackAction

    /** Nothing left to pop: ask before leaving. */
    data object ConfirmExit : BackAction
}

/**
 * One level out.
 *
 * Deepest first, and each level clears only itself. Leaving the Reader keeps
 * [NavSnapshot.openBookId], which is the detail worth stating: a reader who opened a
 * book from its details page and presses Back expects the details page, not the
 * Library — and the Reader is also reachable straight from the Bookmarks list, where
 * there is no details page to return to and Back should land on the Library instead.
 *
 * Only the Library returns [BackAction.ConfirmExit]. Back closed the app from
 * wherever the reader happened to be, which is the wrong end of the gesture: on
 * Android, Back means "out of this", and only the last one out means "done".
 */
fun back(snapshot: NavSnapshot): BackAction = when {
    snapshot.readingOriginal -> BackAction.Pop(snapshot.copy(readingOriginal = false))

    snapshot.readingBookId != null ->
        BackAction.Pop(snapshot.copy(readingBookId = null))

    snapshot.openBookId != null -> BackAction.Pop(snapshot.copy(openBookId = null))

    // Milestones and Level are opened from Streak, so they return to it rather than
    // dropping the reader all the way out of the habit screens.
    snapshot.habitScreen == HabitScreen.MILESTONES ||
        snapshot.habitScreen == HabitScreen.LEVEL ->
        BackAction.Pop(snapshot.copy(habitScreen = HabitScreen.STREAK))

    snapshot.habitScreen != null -> BackAction.Pop(snapshot.copy(habitScreen = null))

    snapshot.destination != QuireDestination.LIBRARY ->
        BackAction.Pop(snapshot.copy(destination = QuireDestination.LIBRARY))

    else -> BackAction.ConfirmExit
}
