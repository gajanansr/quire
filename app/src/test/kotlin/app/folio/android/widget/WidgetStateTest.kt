package app.folio.android.widget

import app.folio.android.data.HabitSummary
import app.folio.core.habit.ReadingDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every sentence and every bar on both widgets.
 *
 * A widget is the least testable surface in Android: it is inflated by the
 * launcher, in another process, on a home screen nothing here can reach. So
 * everything a widget decides is decided in [habitWidget] and [statsWidget], and
 * this is where it is checked — the providers below only transcribe.
 *
 * The rule these tests exist to hold is the one the brief is strictest about: a
 * widget shows what is stored and nothing else. A reader on day one is invited, not
 * congratulated; a reader with three unfinished books is shown three real zeroes,
 * because that is the truth and it is different from having no library at all.
 */
class WidgetStateTest {

    private val today = 20_000L

    private fun day(offset: Int, minutes: Int, goal: Int = 10) =
        ReadingDay(today + offset, minutes, goal)

    private fun summary(
        streak: Int = 0,
        longest: Int = streak,
        goal: Int = 10,
        minutesToday: Int = 0,
        days: List<ReadingDay> = emptyList(),
    ) = HabitSummary(
        days = days,
        today = today,
        goalMinutes = goal,
        minutesToday = minutesToday,
        currentStreak = streak,
        longestStreak = longest,
    )

    private fun snapshot(
        habits: HabitSummary = summary(),
        booksFinished: Int = 0,
        chaptersFinished: Int = 0,
        libraryCount: Int = 0,
        currentBook: CurrentBook? = null,
    ) = WidgetSnapshot(habits, booksFinished, chaptersFinished, libraryCount, currentBook)

    // ------------------------------------------------------------ habit widget

    @Test
    fun `a fresh install is invited to start, not handed a streak`() {
        val state = habitWidget(snapshot())
        assertEquals("Start a reading habit", state.headline)
        assertEquals("10 minutes a day", state.detail)
        assertFalse("the flame is lit on day zero", state.lit)
        assertTrue("a bar is filled with no reading", state.week.none { it.filled })
    }

    @Test
    fun `a streak of one day is one day, not one days`() {
        assertEquals("1 day", habitWidget(snapshot(summary(streak = 1))).headline)
    }

    @Test
    fun `a streak of more than one day is plural`() {
        assertEquals("2 days", habitWidget(snapshot(summary(streak = 2))).headline)
        assertEquals("41 days", habitWidget(snapshot(summary(streak = 41))).headline)
    }

    @Test
    fun `a broken streak invites a new one rather than showing a zero`() {
        // "0 days" on a home screen is a scold. The reading still happened, and the
        // widget is meant to bring someone back, not to mark them down.
        val state = habitWidget(snapshot(summary(streak = 0, longest = 12)))
        assertEquals("Begin a new streak", state.headline)
        assertFalse(state.lit)
    }

    @Test
    fun `a met goal says so instead of reciting minutes`() {
        val state = habitWidget(snapshot(summary(streak = 3, minutesToday = 14)))
        assertEquals("Today's goal is done.", state.detail)
    }

    @Test
    fun `minutes so far are counted against the goal`() {
        val state = habitWidget(snapshot(summary(streak = 3, goal = 20, minutesToday = 6)))
        assertEquals("6 of 20 minutes today", state.detail)
    }

    @Test
    fun `the week is always seven bars, oldest first`() {
        // Drawing only the days that were read would make every reader look perfect.
        val state = habitWidget(
            snapshot(summary(days = listOf(day(-6, 10), day(0, 10))))
        )
        assertEquals(7, state.week.size)
        assertTrue("the oldest day is not first", state.week.first().filled)
        assertTrue("today is not last", state.week.last().filled)
        assertEquals(2, state.week.count { it.filled })
    }

    @Test
    fun `a day with no reading is an empty bar`() {
        val state = habitWidget(snapshot(summary(days = listOf(day(0, 0)))))
        assertTrue(state.week.none { it.filled })
    }

    @Test
    fun `a heavier day is a darker bar`() {
        val state = habitWidget(
            snapshot(summary(goal = 20, days = listOf(day(-1, 4), day(0, 18))))
        )
        val light = state.week[5]
        val heavy = state.week[6]
        assertTrue("a 4-minute day is not lighter than an 18-minute one",
            light.alpha < heavy.alpha)
        assertTrue("a bar that was read is nearly invisible", light.alpha >= 76)
    }

    @Test
    fun `a day past the goal does not overfill its bar`() {
        val state = habitWidget(
            snapshot(summary(goal = 10, days = listOf(day(-1, 10), day(0, 400))))
        )
        assertEquals(255, state.week[5].alpha)
        assertEquals(255, state.week[6].alpha)
    }

    @Test
    fun `a goal of zero is not divided by`() {
        // Only reachable from a corrupt settings row, but the arithmetic is a
        // division and the result would be a crash on someone's home screen.
        val state = habitWidget(snapshot(summary(goal = 0, days = listOf(day(0, 9)))))
        assertTrue("a day that was read is not drawn as read", state.week.last().filled)
        assertEquals(255, state.week.last().alpha)
    }

    @Test
    fun `the flame is lit only when there is a streak`() {
        assertTrue(habitWidget(snapshot(summary(streak = 1))).lit)
        assertFalse(habitWidget(snapshot(summary(streak = 0, longest = 30))).lit)
    }

    // ------------------------------------------------------------ stats widget

    @Test
    fun `an empty library is invited to add a book, not shown three zeroes`() {
        val state = statsWidget(snapshot())
        assertNotNull("a fresh install gets no invitation", state.empty)
        assertEquals("Your library is empty.", state.empty?.title)
        assertEquals("Add a book to get started.", state.empty?.detail)
        assertTrue(state.tiles.isEmpty())
        assertNull(state.current)
    }

    @Test
    fun `real zeroes are shown once there is a library`() {
        // The difference the brief cares about. Zero finished books is a fact about
        // a reader who has three books going; it is not a placeholder.
        val state = statsWidget(snapshot(libraryCount = 3))
        assertNull("a stocked library still shows the empty state", state.empty)
        assertEquals(listOf("0", "0", "0m"), state.tiles.map { it.value })
    }

    @Test
    fun `finished books and chapters count up`() {
        val state = statsWidget(
            snapshot(
                habits = summary(days = listOf(day(-1, 40), day(0, 20))),
                booksFinished = 4,
                chaptersFinished = 37,
                libraryCount = 6,
            )
        )
        assertEquals(listOf("4", "37", "1h"), state.tiles.map { it.value })
        assertEquals(listOf("Books", "Chapters", "Read"), state.tiles.map { it.label })
    }

    @Test
    fun `one book and one chapter are singular`() {
        val state = statsWidget(
            snapshot(booksFinished = 1, chaptersFinished = 1, libraryCount = 1)
        )
        assertEquals(listOf("Book", "Chapter", "Read"), state.tiles.map { it.label })
    }

    @Test
    fun `time reads in minutes under an hour and in hours above it`() {
        assertEquals("45m", readingTime(45))
        assertEquals("59m", readingTime(59))
        assertEquals("1h", readingTime(60))
        assertEquals("1h 1m", readingTime(61))
        assertEquals("3h 20m", readingTime(200))
        assertEquals("0m", readingTime(0))
    }

    @Test
    fun `an exact number of hours drops the minutes`() {
        // "2h 0m" is the tell that a duration was formatted by a machine.
        assertEquals("2h", readingTime(120))
    }

    @Test
    fun `the book being read carries its title and percentage`() {
        val state = statsWidget(
            snapshot(
                libraryCount = 2,
                currentBook = CurrentBook("b1", "The Hobbit", 0.42),
            )
        )
        assertEquals("The Hobbit", state.current?.title)
        assertEquals(42, state.current?.percent)
        assertEquals("42% through", state.current?.detail)
        assertNull("both the book and the prompt are shown", state.prompt)
    }

    @Test
    fun `a percentage is rounded and clamped`() {
        fun percentOf(progress: Double) =
            statsWidget(
                snapshot(libraryCount = 1, currentBook = CurrentBook("b", "t", progress))
            ).current?.percent

        assertEquals(1, percentOf(0.005))
        assertEquals(67, percentOf(0.666))
        assertEquals(100, percentOf(1.4))
        assertEquals(0, percentOf(-0.2))
    }

    @Test
    fun `a library with nothing open says how many books are waiting`() {
        val state = statsWidget(snapshot(libraryCount = 4))
        assertNull(state.current)
        assertEquals("4 books waiting", state.prompt)
    }

    @Test
    fun `one waiting book is singular`() {
        assertEquals("1 book waiting", statsWidget(snapshot(libraryCount = 1)).prompt)
    }

    @Test
    fun `a reader who deleted every book keeps the minutes they read`() {
        // Not the empty state: these numbers were earned, and wiping them the moment
        // the last book is removed would be a different kind of lie.
        val state = statsWidget(
            snapshot(habits = summary(days = listOf(day(0, 30))), booksFinished = 2)
        )
        assertNull(state.empty)
        assertEquals(listOf("2", "0", "30m"), state.tiles.map { it.value })
    }
}
