package app.folio.android.ui.nav

/**
 * Where the reader is standing, as far as Back is concerned.
 *
 * A snapshot of the few `FolioRoot` state variables that stack, rather than a
 * navigation library's back stack. `FolioRoot` switches on these with a `when`, and
 * Back is the same `when` read in reverse — so it is written once, here, where a test
 * can reach it without a device.
 */
data class NavSnapshot(
    /** The original PDF viewer, shown over the Reader for a scanned book. */
    val readingOriginal: Boolean = false,
    val readingBookId: String? = null,
    val openBookId: String? = null,
    val habitScreen: HabitScreen? = null,
    val destination: FolioDestination = FolioDestination.LIBRARY,
)

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

    snapshot.destination != FolioDestination.LIBRARY ->
        BackAction.Pop(snapshot.copy(destination = FolioDestination.LIBRARY))

    else -> BackAction.ConfirmExit
}
