package app.folio.android.widget

import app.folio.android.data.HabitSummary
import app.folio.android.ui.theme.FolioThemeName
import kotlin.math.roundToInt

/**
 * What the widgets say, decided where a test can reach it.
 *
 * Nothing in this file imports anything from Android. That is the point: a widget
 * runs inside the launcher, and there is no way to look at one from a JVM test — so
 * every choice about wording, plurals, arithmetic and bar intensity is made here and
 * the providers do nothing but copy the result into `RemoteViews`. The same split
 * `ReaderState` and `FolioBack` already use, applied where it matters most.
 */

/** The book open right now, as the stats widget needs it. */
data class CurrentBook(val id: String, val title: String, val progress: Double)

/** Everything both widgets are allowed to know, read from storage in one pass. */
data class WidgetSnapshot(
    val habits: HabitSummary = HabitSummary(),
    val booksFinished: Int = 0,
    val chaptersFinished: Int = 0,
    val libraryCount: Int = 0,
    val currentBook: CurrentBook? = null,
    /**
     * The theme the reader chose, which the widget wears too.
     *
     * The one thing on this snapshot that is not a number about reading. It is here
     * rather than fetched separately because the settings row is already read in
     * this pass for the finished counts, and a widget update is a broadcast with
     * about ten seconds to live: a second query would be a second chance to be
     * interrupted and leave the card in last week's palette.
     */
    val theme: FolioThemeName = FolioThemeName.PAPER,
) {
    /**
     * Derived from the recorded days rather than carried alongside them.
     *
     * A stored total would be a second source of truth for the same number, and the
     * one on the home screen would eventually be the stale one.
     */
    val totalMinutes: Int get() = habits.days.sumOf { it.minutes }

    /** True once anything at all has been recorded. Zero is a fact; nothing is not. */
    val hasHistory: Boolean
        get() = libraryCount > 0 || totalMinutes > 0 ||
            booksFinished > 0 || chaptersFinished > 0
}

// ---------------------------------------------------------------- habit widget

/** One day of the week strip. [alpha] is 0..255, applied to the bar's colour. */
data class DayBar(val filled: Boolean, val alpha: Int)

data class HabitWidgetState(
    val headline: String,
    val detail: String,
    val week: List<DayBar>,
    /**
     * What the strip says out loud.
     *
     * Seven unlabelled images is what a screen reader would otherwise announce, so
     * the one fact the bars carry is also said in words.
     */
    val weekDescription: String,
    /** Whether the flame is drawn in the accent colour rather than the muted one. */
    val lit: Boolean,
)

/**
 * The streak, today's minutes, and the week — the three facts the Library's habit
 * card already shows, in the same order.
 *
 * The headline is the number, the way [app.folio.android.ui.habit.StreakScreen]
 * leads with it. A streak of nothing is never "0 days": a zero on a home screen is a
 * scold, and the whole purpose of this widget is to bring someone back.
 */
fun habitWidget(snapshot: WidgetSnapshot): HabitWidgetState {
    val habits = snapshot.habits
    val streak = habits.currentStreak

    val headline = when {
        streak == 1 -> "1 day"
        streak > 1 -> "$streak days"
        habits.longestStreak > 0 -> "Begin a new streak"
        else -> "Start a reading habit"
    }

    // The same three cases the Library card uses, so a reader is not told two
    // different things about the same day on the same phone.
    val detail = when {
        habits.goalMet -> "Today's goal is done."
        habits.minutesToday > 0 ->
            "${habits.minutesToday} of ${habits.goalMinutes} minutes today"
        else -> "${habits.goalMinutes} minutes a day"
    }

    val week = habits.week()
    val read = week.count { it.minutes > 0 }

    return HabitWidgetState(
        headline = headline,
        detail = detail,
        week = week.map { day -> bar(day.minutes, habits.goalMinutes) },
        weekDescription =
            if (read == 0) "No reading in the last ${week.size} days"
            else "Read $read of the last ${week.size} days",
        // A lit flame only once there is a streak to show, matching StreakScreen.
        // Drawing it in the accent colour on day zero congratulates someone for
        // nothing, which is the thing that makes a habit widget feel fake.
        lit = streak > 0,
    )
}

/**
 * One day as a bar.
 *
 * Fullness is how much of the goal was met, ramped from 0.3 so a short day is still
 * visibly a day — the same curve the Library card draws. Whether a bar is filled at
 * all is decided by the minutes and not by the ratio: a goal of zero would otherwise
 * erase a day that really was read.
 */
private fun bar(minutes: Int, goalMinutes: Int): DayBar {
    if (minutes <= 0) return DayBar(filled = false, alpha = FULL)
    val ratio =
        if (goalMinutes <= 0) 1f
        else (minutes.toFloat() / goalMinutes).coerceIn(0f, 1f)
    return DayBar(filled = true, alpha = ((0.3f + 0.7f * ratio) * FULL).roundToInt())
}

private const val FULL = 255

// ---------------------------------------------------------------- stats widget

data class Tile(val value: String, val label: String)

data class CurrentReading(val title: String, val percent: Int, val detail: String)

/** Shown instead of everything else, when there is nothing else to show. */
data class EmptyMessage(val title: String, val detail: String)

data class StatsWidgetState(
    val tiles: List<Tile>,
    val current: CurrentReading?,
    /** Stands in for [current] when the library has nothing open. */
    val prompt: String?,
    val empty: EmptyMessage?,
)

/**
 * Books finished, chapters finished, time read, and what is open now.
 *
 * The empty state is reached only when nothing has ever been recorded. A reader
 * three books into their library who has finished none of them sees three zeroes,
 * because that is true — and it is a different thing from having no library, which
 * is why the two are not collapsed.
 */
fun statsWidget(snapshot: WidgetSnapshot): StatsWidgetState {
    if (!snapshot.hasHistory) {
        return StatsWidgetState(
            tiles = emptyList(),
            current = null,
            prompt = null,
            // The Library's own words, so the two surfaces agree.
            empty = EmptyMessage("Your library is empty.", "Add a book to get started."),
        )
    }

    val tiles = listOf(
        Tile(
            snapshot.booksFinished.toString(),
            if (snapshot.booksFinished == 1) "Book" else "Books",
        ),
        Tile(
            snapshot.chaptersFinished.toString(),
            if (snapshot.chaptersFinished == 1) "Chapter" else "Chapters",
        ),
        Tile(readingTime(snapshot.totalMinutes), "Read"),
    )

    val current = snapshot.currentBook?.let { book ->
        val percent = (book.progress * 100).roundToInt().coerceIn(0, 100)
        CurrentReading(book.title, percent, "$percent% through")
    }

    val prompt = when {
        current != null -> null
        snapshot.libraryCount == 1 -> "1 book waiting"
        snapshot.libraryCount > 1 -> "${snapshot.libraryCount} books waiting"
        else -> null
    }

    return StatsWidgetState(tiles = tiles, current = current, prompt = prompt, empty = null)
}

/**
 * Minutes as a reader would say them.
 *
 * "2h 0m" is the tell that a duration was formatted by a machine rather than
 * written, so a whole number of hours drops the minutes entirely.
 */
fun readingTime(minutes: Int): String {
    val total = minutes.coerceAtLeast(0)
    val hours = total / 60
    val rest = total % 60
    return when {
        hours == 0 -> "${rest}m"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}
