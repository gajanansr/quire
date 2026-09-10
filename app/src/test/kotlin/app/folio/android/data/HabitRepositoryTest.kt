package app.folio.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.folio.core.habit.DayMinutes
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

    private lateinit var db: FolioDatabase
    private lateinit var habits: HabitRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), FolioDatabase::class.java,
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
    fun `only the handoff's four goals are accepted`() = runBlocking {
        HabitRepository.GOAL_OPTIONS.forEach { habits.setDailyGoal(it) }
        val rejected = runCatching { habits.setDailyGoal(17) }
        assertTrue("an unsupported goal was accepted", rejected.isFailure)
    }

    @Test
    fun `settings round trip`() = runBlocking {
        habits.setTheme("DARK")
        habits.setReaderPreferences(font = "LORA", sizeSp = 22f, justify = true)
        val settings = habits.settings()
        assertEquals("DARK", settings.themeName)
        assertEquals("LORA", settings.readerFont)
        assertEquals(22f, settings.readerFontSizeSp, 0.01f)
        assertTrue(settings.readerJustify)
    }
}
