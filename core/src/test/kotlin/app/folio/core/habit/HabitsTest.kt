package app.folio.core.habit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreaksTest {

    private val today = 20_000L   // an arbitrary epoch-day

    private fun day(offset: Long, minutes: Int = 20, goal: Int = 20) =
        ReadingDay(today - offset, minutes, goal)

    @Test
    fun `a new reader has no streak`() {
        assertEquals(0, Streaks.current(emptyList(), today))
        assertEquals(0, Streaks.longest(emptyList()))
    }

    @Test
    fun `reading today is a streak of one`() {
        assertEquals(1, Streaks.current(listOf(day(0)), today))
    }

    @Test
    fun `consecutive days accumulate`() {
        val days = (0L..4L).map { day(it) }
        assertEquals(5, Streaks.current(days, today))
    }

    @Test
    fun `a streak ending yesterday still counts today`() {
        // At 00:01 the reader has not had a chance to read yet. Zeroing the streak
        // the instant midnight passes would be both wrong and discouraging.
        val days = (1L..3L).map { day(it) }
        assertEquals(3, Streaks.current(days, today))
    }

    @Test
    fun `a streak breaks once a whole day has been missed`() {
        // Last read two days ago: today and yesterday are both empty.
        val days = (2L..4L).map { day(it) }
        assertEquals(0, Streaks.current(days, today))
    }

    @Test
    fun `a day below the goal does not extend the streak`() {
        val days = listOf(day(0), day(1, minutes = 5, goal = 20), day(2))
        assertEquals(1, Streaks.current(days, today))
    }

    @Test
    fun `a day with no goal set cannot meet it`() {
        assertFalse(ReadingDay(today, minutes = 60, goalMinutes = 0).metGoal)
    }

    @Test
    fun `meeting the goal exactly counts`() {
        assertTrue(ReadingDay(today, minutes = 20, goalMinutes = 20).metGoal)
    }

    @Test
    fun `the longest streak survives the current one ending`() {
        // A seven-day run last month, nothing since.
        val old = (30L..36L).map { day(it) }
        assertEquals(0, Streaks.current(old, today))
        assertEquals(7, Streaks.longest(old))
    }

    @Test
    fun `the longest streak is the best run, not the total`() {
        val days = (0L..2L).map { day(it) } + (10L..14L).map { day(it) }
        assertEquals(5, Streaks.longest(days))
    }

    @Test
    fun `duplicate entries for a day do not inflate a streak`() {
        // Two rollups for the same day must not read as two consecutive days.
        val days = listOf(day(0), day(0), day(1))
        assertEquals(2, Streaks.current(days, today))
        assertEquals(2, Streaks.longest(days))
    }

    @Test
    fun `out of order history is handled`() {
        val days = listOf(day(2), day(0), day(1))
        assertEquals(3, Streaks.current(days, today))
    }

    @Test
    fun `the heatmap window includes days with no reading`() {
        val window = Streaks.window(listOf(day(0), day(3)), today, count = 7)
        assertEquals(7, window.size)
        assertEquals(today, window.last().epochDay)
        assertEquals(2, window.count { it.minutes > 0 })
        assertTrue(window.none { it.epochDay > today })
    }
}

class LevelsTest {

    @Test
    fun `a new reader is at the first level with no xp`() {
        assertEquals(0, Levels.xpFor(0, 0))
        assertEquals("New Reader", Levels.levelFor(0).name)
    }

    @Test
    fun `xp comes from minutes and finished books`() {
        assertEquals(60, Levels.xpFor(minutesRead = 60, booksFinished = 0))
        assertEquals(160, Levels.xpFor(minutesRead = 60, booksFinished = 1))
    }

    @Test
    fun `negative inputs cannot reduce xp`() {
        assertEquals(0, Levels.xpFor(minutesRead = -100, booksFinished = -3))
    }

    @Test
    fun `levels rise with xp and never skip backwards`() {
        var previous = -1
        listOf(0, 149, 150, 399, 400, 899, 900, 1_799, 1_800, 3_499, 3_500, 99_999)
            .forEach { xp ->
                val level = Levels.levelFor(xp).index
                assertTrue(level >= previous, "level went backwards at $xp")
                previous = level
            }
    }

    @Test
    fun `the handoff's six levels exist`() {
        assertEquals(6, Levels.all.size)
        assertTrue(Levels.all.any { it.name == "Regular Reader" })
    }

    @Test
    fun `progress within a level runs from zero to one`() {
        assertEquals(0.0, Levels.progressWithinLevel(0), 1e-9)
        assertTrue(Levels.progressWithinLevel(275) in 0.4..0.6)
        assertEquals(0.0, Levels.progressWithinLevel(150), 1e-9)
    }

    @Test
    fun `the top level is complete and has no next`() {
        val top = Levels.all.last().minXp + 10_000
        assertEquals(1.0, Levels.progressWithinLevel(top), 1e-9)
        assertEquals(null, Levels.nextLevel(top))
    }
}

class MilestonesTest {

    @Test
    fun `a new reader has achieved nothing`() {
        assertTrue(Milestones.achieved(HabitStats()).isEmpty())
        assertEquals(Milestones.all.size, Milestones.locked(HabitStats()).size)
    }

    @Test
    fun `reading at all earns the first milestone`() {
        val achieved = Milestones.achieved(HabitStats(totalMinutes = 1))
        assertTrue(achieved.any { it.id == "first_page" })
    }

    @Test
    fun `milestones unlock at their thresholds`() {
        val stats = HabitStats(
            booksFinished = 1, chaptersFinished = 3, totalMinutes = 620,
            currentStreak = 3, longestStreak = 7, daysRead = 9,
        )
        val ids = Milestones.achieved(stats).map { it.id }
        assertTrue(ids.containsAll(listOf(
            "first_page", "first_ten", "first_chapter", "first_book",
            "three_days", "one_week", "ten_hours",
        )))
        assertFalse(ids.contains("five_books"), "five books should still be locked")
    }

    @Test
    fun `achieved and locked always partition the whole set`() {
        listOf(
            HabitStats(),
            HabitStats(totalMinutes = 30),
            HabitStats(booksFinished = 9, totalMinutes = 5_000, longestStreak = 40),
        ).forEach { stats ->
            val achieved = Milestones.achieved(stats)
            val locked = Milestones.locked(stats)
            assertEquals(Milestones.all.size, achieved.size + locked.size)
            assertTrue((achieved intersect locked.toSet()).isEmpty())
        }
    }

    @Test
    fun `milestones carry no count, only a name and a sentence`() {
        // The handoff says "no numeric badge counts" — no "5 of 8" chips. It does
        // not forbid numbers in a name: "First 10 Minutes" is the design's own
        // copy. So the rule to enforce is structural, not textual.
        Milestones.all.forEach { m ->
            assertTrue(m.title.isNotBlank(), "a milestone has no name")
            assertTrue(m.detail.isNotBlank(), "'${m.title}' has no sentence")
            assertTrue(
                m.detail.endsWith("."),
                "'${m.detail}' should read as a sentence, not a label",
            )
        }
        // A count would have to live on the type, and Milestone has no field for
        // one: id, title, detail. That is the structural guarantee.
    }
}
