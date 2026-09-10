package app.folio.core.habit

import app.folio.core.FolioConstants
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionAccumulatorTest {

    private val dayMs = 24L * 60 * 60 * 1000
    private val minute = 60_000L

    /** A fixed UTC-like clock: day boundaries every 24h from epoch. */
    private val accumulator = SessionAccumulator(
        startOfDayMs = { t -> (t / dayMs) * dayMs },
        epochDayOf = { t -> t / dayMs },
    )

    /** 10:00 on epoch-day 20000. */
    private val tenAm = 20_000L * dayMs + 10 * 60 * minute

    private fun minutesOn(day: Long, credits: List<DayMinutes>) =
        credits.firstOrNull { it.epochDay == day }?.minutes ?: 0

    // ------------------------------------------------------------ the basics

    @Test
    fun `no activity records nothing`() {
        assertTrue(accumulator.creditFor(emptyList(), null).isEmpty())
    }

    @Test
    fun `continuous reading is credited in whole minutes`() {
        // A page turn each minute for twenty minutes.
        val activity = (0..20).map { tenAm + it * minute }
        val credits = accumulator.creditFor(activity, endedAtMs = tenAm + 20 * minute)
        assertEquals(20, minutesOn(20_000L, credits))
    }

    @Test
    fun `a glance shorter than a minute records nothing`() {
        // Opening a book and closing it again is not reading.
        val credits = accumulator.creditFor(listOf(tenAm), endedAtMs = tenAm + 5_000)
        assertTrue(credits.isEmpty(), "a five-second glance was recorded: $credits")
    }

    // ------------------------------------------------------------------ idle

    @Test
    fun `an hour of idle time is not an hour of reading`() {
        // Read for five minutes, then leave the phone on the page for an hour.
        val activity = (0..5).map { tenAm + it * minute }
        val credits = accumulator.creditFor(activity, endedAtMs = tenAm + 65 * minute)

        val recorded = minutesOn(20_000L, credits)
        assertTrue(
            recorded <= 5 + FolioConstants.IDLE_TIMEOUT_MINUTES,
            "credited $recorded minutes for five minutes of reading",
        )
    }

    @Test
    fun `a gap longer than the timeout splits the session`() {
        val first = (0..3).map { tenAm + it * minute }
        val second = (0..3).map { tenAm + (30 + it) * minute }
        val intervals = accumulator.intervals(first + second, endedAtMs = tenAm + 33 * minute)
        assertEquals(2, intervals.size)
    }

    @Test
    fun `a short pause does not split the session`() {
        // Below the idle timeout: a reader pausing to think is still reading.
        val activity = listOf(tenAm, tenAm + minute, tenAm + 2 * minute + 30_000)
        assertEquals(1, accumulator.intervals(activity, endedAtMs = tenAm + 3 * minute).size)
    }

    @Test
    fun `an abandoned session is closed at the idle cutoff, not left open`() {
        // No explicit end: the app was killed. The interval must still terminate.
        val activity = listOf(tenAm, tenAm + minute)
        val intervals = accumulator.intervals(activity, endedAtMs = null)
        assertEquals(1, intervals.size)
        val cutoff = FolioConstants.IDLE_TIMEOUT_MINUTES * minute
        assertEquals(tenAm + minute + cutoff, intervals.single().endMs)
    }

    // -------------------------------------------------------------- midnight

    @Test
    fun `reading across midnight is split between the two days`() {
        // 23:40 to 00:20 is twenty minutes each day, not forty on either — the
        // difference between earning a streak day and being handed one.
        val elevenForty = 20_000L * dayMs + (23 * 60 + 40) * minute
        val activity = (0..40).map { elevenForty + it * minute }
        val credits = accumulator.creditFor(activity, endedAtMs = elevenForty + 40 * minute)

        assertEquals(20, minutesOn(20_000L, credits))
        assertEquals(20, minutesOn(20_001L, credits))
    }

    @Test
    fun `a session entirely before midnight credits only that day`() {
        val activity = (0..10).map { tenAm + it * minute }
        val credits = accumulator.creditFor(activity, endedAtMs = tenAm + 10 * minute)
        assertEquals(listOf(20_000L), credits.map { it.epochDay })
    }

    // ---------------------------------------------------------------- totals

    @Test
    fun `two sessions in one day sum`() {
        val morning = (0..10).map { tenAm + it * minute }
        val evening = (0..15).map { tenAm + (600 + it) * minute }

        val a = accumulator.creditFor(morning, tenAm + 10 * minute)
        val b = accumulator.creditFor(evening, tenAm + 615 * minute)

        assertEquals(10, minutesOn(20_000L, a))
        assertEquals(15, minutesOn(20_000L, b))
    }

    @Test
    fun `activity arriving out of order is handled`() {
        val activity = listOf(tenAm + 3 * minute, tenAm, tenAm + minute, tenAm + 2 * minute)
        val credits = accumulator.creditFor(activity, endedAtMs = tenAm + 3 * minute)
        assertEquals(3, minutesOn(20_000L, credits))
    }

    @Test
    fun `an end before the last activity cannot produce negative time`() {
        val activity = listOf(tenAm, tenAm + 5 * minute)
        val credits = accumulator.creditFor(activity, endedAtMs = tenAm)
        credits.forEach { assertTrue(it.minutes >= 0, "negative minutes: $it") }
    }
}
