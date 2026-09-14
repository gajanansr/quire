package app.quire.core.habit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The forgiving streak.
 *
 * Every assertion here is about one of the three properties the design is allowed
 * to have: a missed day does not end a run, a *pattern* of missed days does, and the
 * number on screen is never larger than the number of days the reader actually read.
 * The third is the one that matters most — a streak that quietly repairs itself is a
 * lie about what happened, and a lie is not forgiveness.
 */
class RestDaysTest {

    private val today = 20_000L

    private fun day(offset: Long, minutes: Int = 20, goal: Int = 20) =
        ReadingDay(today - offset, minutes, goal)

    /** Days read, counted back from [today], as the run reports them. */
    private fun run(vararg offsets: Long) = Streaks.run(offsets.map { day(it) }, today)

    // ------------------------------------------------- a missed day is survivable

    @Test
    fun `a single missed day does not end the run`() {
        // Read four days, missed the fifth-from-today. Under the old rule this was
        // a streak of two; the whole point of the change is that it is not.
        val days = listOf(day(0), day(1), day(3), day(4))
        val run = Streaks.run(days, today)
        assertEquals(4, run.daysRead)
        assertEquals(listOf(today - 2), run.restDays)
    }

    @Test
    fun `missing yesterday leaves the run standing today`() {
        // The case the reader actually experiences: they open Quire the morning
        // after a day they did not read. If the number has already gone to zero,
        // the forgiveness is invisible and might as well not exist.
        val days = (2L..6L).map { day(it) }
        val run = Streaks.run(days, today)
        assertEquals(5, run.daysRead)
        assertEquals(listOf(today - 1), run.restDays)
    }

    @Test
    fun `today being unread is not a rest day, because the day is not over`() {
        // At 00:01 nobody has had a chance to read. Spending a rest day on the
        // hours before breakfast would mean a reader who reads every evening burns
        // one every single morning.
        val days = (1L..4L).map { day(it) }
        val run = Streaks.run(days, today)
        assertEquals(4, run.daysRead)
        assertTrue(run.restDays.isEmpty(), "today was counted as a rest day: ${run.restDays}")
    }

    // ------------------------------------------------- a pattern of them is not

    @Test
    fun `two missed days in a row end the run`() {
        // "Never miss twice" is the rule underneath this. One day is life; two in a
        // row is the habit stopping, and a streak that survives it is not measuring
        // anything.
        val days = listOf(day(0), day(3), day(4), day(5))
        assertEquals(1, Streaks.run(days, today).daysRead)
    }

    @Test
    fun `a second rest day inside the same week ends the run`() {
        // One rest day in any seven. Read every day except two-days-ago and
        // five-days-ago: the second is only three days from the first, so the run
        // stops there rather than absorbing both.
        val days = listOf(day(0), day(1), day(3), day(4), day(6), day(7), day(8))
        val run = Streaks.run(days, today)
        assertEquals(4, run.daysRead)
        assertEquals(listOf(today - 2), run.restDays)
    }

    @Test
    fun `a rest day a full week after the last one is allowed`() {
        // Exactly [Streaks.REST_EVERY_DAYS] apart is far enough. The boundary is
        // stated here because an off-by-one either forgives twice a week or never
        // forgives at all after the first time, and both look plausible on screen.
        val missed = setOf(today - 2, today - 9)
        val days = (0L..14L).map { today - it }
            .filterNot { it in missed }
            .map { ReadingDay(it, 20, 20) }
        val run = Streaks.run(days, today)
        assertEquals(13, run.daysRead)
        assertEquals(listOf(today - 2, today - 9), run.restDays)
    }

    @Test
    fun `an every-other-day reader does not accumulate an endless streak`() {
        // The dishonest failure mode in the other direction. Someone who reads three
        // times a week is not on a hundred-day streak, and a mechanic that told them
        // so would be flattery, not forgiveness.
        val days = (0L..60L step 2).map { day(it) }
        val run = Streaks.run(days, today)
        assertEquals(2, run.daysRead)
    }

    // ------------------------------------------------------------- honesty

    @Test
    fun `the number is never larger than the days actually read`() {
        // The load-bearing invariant, over a lot of shapes rather than one. Whatever
        // the run forgives, the figure on the streak screen counts days on which the
        // reader met their goal and nothing else.
        val shapes = listOf(
            (0L..30L).toList(),
            listOf(0L, 1L, 3L, 4L, 5L, 12L, 13L),
            (0L..40L step 3).toList(),
            listOf(0L, 7L, 14L, 21L),
            emptyList(),
            listOf(0L),
            (0L..10L).toList() + (20L..30L).toList(),
        )
        shapes.forEach { offsets ->
            val days = offsets.map { day(it) }
            val run = Streaks.run(days, today)
            val start = run.startDay
            if (start == null) {
                assertEquals(0, run.daysRead, "a run with no start still counted days")
                return@forEach
            }
            val reallyRead = days.count { it.metGoal && it.epochDay >= start }
            assertEquals(
                reallyRead, run.daysRead,
                "the run counted days the reader did not read: $offsets",
            )
            assertTrue(
                run.restDays.none { rest -> days.any { it.epochDay == rest && it.metGoal } },
                "a day that was read was reported as a rest day: $offsets",
            )
        }
    }

    @Test
    fun `a rest day is never reported outside the run it belongs to`() {
        // A reader who started five days ago and has read since. The day before
        // they started is unread, and the walk reaches it — but forgiving it would
        // draw a rest mark on the heatmap for a day the run never covered, and
        // would suggest the reader lapsed before they had begun.
        val days = (0L..4L).map { day(it) }
        val run = Streaks.run(days, today)
        val start = run.startDay!!
        assertEquals(today - 4, start)
        assertTrue(
            run.restDays.all { it > start },
            "a rest day older than the run's first day: ${run.restDays}",
        )
        assertTrue(run.restDays.isEmpty(), "a dangling rest day: ${run.restDays}")
    }

    @Test
    fun `a day below the goal is a missed day, not a read one`() {
        val days = listOf(day(0), day(1, minutes = 4), day(2))
        val run = Streaks.run(days, today)
        assertEquals(2, run.daysRead)
        assertEquals(listOf(today - 1), run.restDays)
    }

    @Test
    fun `a reader who has never read has no run and no rest days`() {
        val run = Streaks.run(emptyList(), today)
        assertEquals(0, run.daysRead)
        assertTrue(run.restDays.isEmpty())
        assertNull(run.startDay)
    }

    @Test
    fun `a run that ended long ago is over, forgiveness or not`() {
        // Forgiveness spans a gap; it does not resurrect. Someone who stopped a
        // month ago starts again at one, and the longest run is what survives.
        val days = (30L..36L).map { day(it) }
        assertEquals(0, Streaks.run(days, today).daysRead)
        assertEquals(7, Streaks.longestRun(days))
    }

    @Test
    fun `duplicate rollups for one day cannot inflate a run`() {
        val days = listOf(day(0), day(0), day(1))
        assertEquals(2, Streaks.run(days, today).daysRead)
        assertEquals(2, Streaks.longestRun(days))
    }

    @Test
    fun `out of order history is handled`() {
        val days = listOf(day(3), day(0), day(4), day(1))
        assertEquals(4, Streaks.run(days, today).daysRead)
    }

    // ------------------------------------------- the strict count still exists

    @Test
    fun `no forgiveness is the old rule, exactly`() {
        // `Streaks.current` is the same walk with the allowance switched off, which
        // is what keeps one algorithm rather than two that drift apart. Milestones
        // that claim the reader read *every* day still ask this one.
        val days = listOf(day(0), day(1), day(3), day(4))
        assertEquals(2, Streaks.current(days, today))
        assertEquals(4, Streaks.run(days, today).daysRead)
    }

    @Test
    fun `the longest run forgives on the same terms as the current one`() {
        // Read a fortnight with one day missed in the middle. The best run is the
        // whole fortnight minus that day, not the longer of the two halves.
        val days = (0L..13L).filterNot { it == 6L }.map { day(it) }
        assertEquals(13, Streaks.longestRun(days))
        assertEquals(7, Streaks.longest(days))
    }

    @Test
    fun `the longest run never exceeds the days read`() {
        val offsets = listOf(0L, 1L, 2L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 18L, 19L)
        val days = offsets.map { day(it) }
        assertTrue(
            Streaks.longestRun(days) <= days.count { it.metGoal },
            "the longest run counted more days than were read",
        )
    }

    @Test
    fun `one rest day a week is the shipped allowance`() {
        // Named rather than assumed, because the copy on the streak screen says
        // "one rest day a week" in so many words and the two must agree.
        assertEquals(7, Streaks.REST_EVERY_DAYS)
    }
}
