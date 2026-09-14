package app.quire.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.quire.core.habit.DayMinutes
import app.quire.core.habit.Goals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class HabitRepositoryTest {

    private lateinit var db: QuireDatabase
    private lateinit var habits: HabitRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), QuireDatabase::class.java,
        ).allowMainThreadQueries().build()
        habits = HabitRepository(db, zone = { ZoneId.of("UTC") })
    }

    @After
    fun tearDown() = db.close()

    private fun today() = habits.todayEpochDay()

    @Test
    fun `a new reader sees zeroes, not a flattering start`() = runBlocking {
        val summary = habits.observeSummary().first()
        assertEquals(0, summary.currentStreak)
        assertEquals(0, summary.longestStreak)
        assertEquals(0, summary.xp)
        assertEquals(0, summary.minutesToday)
        assertTrue(summary.achieved.isEmpty())
        assertFalse(summary.goalMet)
    }

    @Test
    fun `recorded minutes appear in today's total`() = runBlocking {
        habits.record(listOf(DayMinutes(today(), 12)))
        assertEquals(12, habits.observeSummary().first().minutesToday)
    }

    @Test
    fun `two sessions on one day add up rather than overwrite`() = runBlocking {
        // The second session must not replace the first.
        habits.record(listOf(DayMinutes(today(), 8)))
        habits.record(listOf(DayMinutes(today(), 7)))
        assertEquals(15, habits.observeSummary().first().minutesToday)
    }

    @Test
    fun `meeting the goal starts a streak`() = runBlocking {
        habits.setDailyGoal(10)
        habits.record(listOf(DayMinutes(today(), 10)))
        val summary = habits.observeSummary().first()
        assertTrue(summary.goalMet)
        assertEquals(1, summary.currentStreak)
    }

    @Test
    fun `falling short of the goal starts no streak`() = runBlocking {
        habits.setDailyGoal(20)
        habits.record(listOf(DayMinutes(today(), 5)))
        val summary = habits.observeSummary().first()
        assertFalse(summary.goalMet)
        assertEquals(0, summary.currentStreak)
    }

    @Test
    fun `changing the goal does not rewrite whether past days were met`() = runBlocking {
        // Someone who met a 5-minute goal last week still met it, even if they
        // later aim for 30. Rewriting history would erase earned streaks.
        habits.setDailyGoal(5)
        habits.record(listOf(DayMinutes(today() - 1, 6)))
        habits.setDailyGoal(30)

        val past = habits.observeSummary().first().days.first { it.epochDay == today() - 1 }
        assertEquals(5, past.goalMinutes)
        assertTrue(past.metGoal)
    }

    @Test
    fun `xp and level rise with recorded minutes`() = runBlocking {
        habits.record(listOf(DayMinutes(today(), 200)))
        val summary = habits.observeSummary().first()
        assertEquals(200, summary.xp)
        assertEquals("Curious", summary.level.name)
        assertTrue(summary.levelProgress in 0.0..1.0)
    }

    @Test
    fun `finishing a book adds xp`() = runBlocking {
        habits.record(listOf(DayMinutes(today(), 10)))
        val before = habits.observeSummary().first().xp
        habits.recordBookFinished()
        assertEquals(before + 100, habits.observeSummary().first().xp)
    }

    @Test
    fun `milestones unlock from real activity`() = runBlocking {
        habits.record(listOf(DayMinutes(today(), 12)))
        val ids = habits.observeSummary().first().achieved.map { it.id }
        assertTrue(ids.contains("first_page"))
        assertTrue(ids.contains("first_ten"))
        assertFalse(ids.contains("first_book"))
    }

    @Test
    fun `the week strip always has seven days, ending today`() = runBlocking {
        habits.record(listOf(DayMinutes(today(), 10)))
        val week = habits.observeSummary().first().week()
        assertEquals(7, week.size)
        assertEquals(today(), week.last().epochDay)
    }

    @Test
    fun `the heatmap covers four weeks`() = runBlocking {
        assertEquals(28, habits.observeSummary().first().month().size)
    }

    @Test
    fun `any goal a reader can name is stored exactly`() = runBlocking {
        // Replaces `only the handoff's four goals are accepted`, which asserted the
        // behaviour this change exists to remove: the goal was a four-value cycle
        // and `setDailyGoal` threw on anything else, so a reader who wanted fifteen
        // minutes simply could not have it.
        listOf(1, 7, 15, 17, 45, 90, 120).forEach { minutes ->
            habits.setDailyGoal(minutes)
            assertEquals(
                "the goal $minutes did not survive being set",
                minutes,
                habits.settings().dailyGoalMinutes,
            )
        }
        HabitRepository.GOAL_OPTIONS.forEach { minutes ->
            habits.setDailyGoal(minutes)
            assertEquals(minutes, habits.settings().dailyGoalMinutes)
        }
    }

    @Test
    fun `a goal outside the range is clamped rather than thrown`() = runBlocking {
        // A throw here used to be safe because only four buttons could reach it.
        // With a stepper behind it, a throw is an uncaught exception inside a
        // coroutine launched from a composable — a crash on a tap.
        habits.setDailyGoal(0)
        assertEquals(Goals.MIN, habits.settings().dailyGoalMinutes)
        habits.setDailyGoal(10_000)
        assertEquals(Goals.MAX, habits.settings().dailyGoalMinutes)
    }

    @Test
    fun `a goal set to the new extremes still cannot rewrite history`() = runBlocking {
        // The existing test above proves a goal change does not rewrite a past day.
        // This one proves it at the ends of the range the reader can now reach: with
        // a free choice, the cheapest way to manufacture a streak would be to drop
        // the goal to one minute and watch months of history light up, and the
        // cheapest way to destroy one would be to raise it to two hours.
        habits.setDailyGoal(20)
        habits.record(listOf(DayMinutes(today() - 1, minutes = 4)))
        habits.record(listOf(DayMinutes(today() - 2, minutes = 25)))

        habits.setDailyGoal(Goals.MIN)
        val cheap = habits.observeSummary().first()
        assertFalse(
            "a day that fell short was retroactively made into a streak day",
            cheap.days.first { it.epochDay == today() - 1 }.metGoal,
        )

        habits.setDailyGoal(Goals.MAX)
        val harsh = habits.observeSummary().first()
        assertTrue(
            "a day that met its goal stopped counting when the goal was raised",
            harsh.days.first { it.epochDay == today() - 2 }.metGoal,
        )
    }

    @Test
    fun `a missed day does not end the run, and is reported rather than hidden`() = runBlocking {
        // The forgiving streak, through the whole stack rather than only in :core.
        // The reader read on four of the last five days; the figure is four, not
        // five, and the day they did not read comes back as a rest day so the
        // heatmap and the sentence under the number can both say so.
        habits.setDailyGoal(10)
        listOf(0L, 1L, 3L, 4L).forEach {
            habits.record(listOf(DayMinutes(today() - it, minutes = 12)))
        }

        val summary = habits.observeSummary().first()
        assertEquals("the run did not survive one missed day", 4, summary.currentStreak)
        assertEquals(listOf(today() - 2), summary.restDays)
        assertEquals(today() - 4, summary.streakStart)
        assertEquals(
            "the run counted a day the reader did not read",
            summary.days.count { it.metGoal },
            summary.currentStreak,
        )
    }

    @Test
    fun `settings round trip`() = runBlocking {
        habits.setTheme("NIGHT")
        habits.setReaderPreferences(font = "LORA", sizeSp = 22f, justify = true)
        val settings = habits.settings()
        assertEquals("NIGHT", settings.themeName)
        assertEquals("LORA", settings.readerFont)
        assertEquals(22f, settings.readerFontSizeSp, 0.01f)
        assertTrue(settings.readerJustify)
    }
}
