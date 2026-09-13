package app.folio.android.notify

import app.folio.android.data.AppSettingsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long until the next reminder.
 *
 * Small arithmetic, and exactly the kind that is wrong by a day for one pair of
 * inputs nobody thinks to try — the reader whose reminder is set for the hour that
 * has just passed, or for midnight. WorkManager gives no second chance: a delay
 * computed wrong here is a reminder that arrives tomorrow, or twice.
 */
class ReminderScheduleTest {

    private val day = Reminders.MINUTES_PER_DAY

    @Test
    fun `a time still ahead today is reached today`() {
        assertEquals(720, Reminders.minutesUntil(nowMinuteOfDay = 8 * 60, targetMinuteOfDay = 20 * 60))
    }

    @Test
    fun `a time already past today is reached tomorrow`() {
        assertEquals(
            23 * 60,
            Reminders.minutesUntil(nowMinuteOfDay = 21 * 60, targetMinuteOfDay = 20 * 60),
        )
    }

    @Test
    fun `turning reminders on at exactly the chosen time waits for tomorrow`() {
        // The alternative is a phone that buzzes in the same instant the reader
        // flips the switch, which reads as a bug even to someone who wanted it.
        assertEquals(day, Reminders.minutesUntil(20 * 60, 20 * 60))
    }

    @Test
    fun `midnight is reached from the last minute of the day`() {
        assertEquals(1, Reminders.minutesUntil(nowMinuteOfDay = day - 1, targetMinuteOfDay = 0))
    }

    @Test
    fun `waiting that long always lands exactly on the chosen time`() {
        // The property, rather than a handful of examples: whatever the pair, now
        // plus the delay is the target — on today's clock or tomorrow's.
        (0 until day step 7).forEach { now ->
            (0 until day step 11).forEach { target ->
                val delay = Reminders.minutesUntil(now, target)
                assertEquals(
                    "waiting $delay from $now did not land on $target",
                    target,
                    (now + delay) % day,
                )
            }
        }
    }

    // ------------------------------------------------------ saying the time

    @Test
    fun `noon and midnight are where a twelve-hour clock always goes wrong`() {
        // Both are hour 0 modulo 12, and the naive conversion writes them as "0:00".
        assertEquals("12:00 am", Reminders.formatTime(0, use24Hour = false))
        assertEquals("12:00 pm", Reminders.formatTime(12 * 60, use24Hour = false))
        assertEquals("12:30 am", Reminders.formatTime(30, use24Hour = false))
        assertEquals("12:30 pm", Reminders.formatTime(12 * 60 + 30, use24Hour = false))
    }

    @Test
    fun `the ordinary times read the way a person would write them`() {
        assertEquals("8:00 pm", Reminders.formatTime(20 * 60, use24Hour = false))
        assertEquals("7:05 am", Reminders.formatTime(7 * 60 + 5, use24Hour = false))
        assertEquals("11:59 pm", Reminders.formatTime(day - 1, use24Hour = false))
        assertEquals("20:00", Reminders.formatTime(20 * 60, use24Hour = true))
        assertEquals("07:05", Reminders.formatTime(7 * 60 + 5, use24Hour = true))
        assertEquals("00:00", Reminders.formatTime(0, use24Hour = true))
    }

    @Test
    fun `every minute of the day can be said out loud`() {
        (0 until day).forEach { minute ->
            val twelve = Reminders.formatTime(minute, use24Hour = false)
            val twentyFour = Reminders.formatTime(minute, use24Hour = true)
            assertTrue("minute $minute has no 12-hour form", twelve.isNotBlank())
            assertTrue("minute $minute has no 24-hour form", twentyFour.isNotBlank())
            val hour = twelve.substringBefore(':').toInt()
            assertTrue("a 12-hour clock showing $hour at minute $minute", hour in 1..12)
            assertEquals("minute $minute lost its padding: $twentyFour", 5, twentyFour.length)
        }
    }

    // ------------------------------------------------ the times on offer

    @Test
    fun `every time a reader can pick is a real time of day`() {
        Reminders.TIME_OPTIONS.forEach { minute ->
            assertTrue("$minute is not a time of day", minute in 0 until day)
            assertTrue(
                "$minute would show as a ragged time on a chip",
                minute % 30 == 0,
            )
        }
    }

    @Test
    fun `the times are offered in the order a clock runs`() {
        assertEquals(
            "the time chips are out of order",
            Reminders.TIME_OPTIONS.sorted(),
            Reminders.TIME_OPTIONS,
        )
        assertEquals(
            "a time is offered twice",
            Reminders.TIME_OPTIONS.distinct().size,
            Reminders.TIME_OPTIONS.size,
        )
    }

    @Test
    fun `the default reminder time is one a reader can get back to`() {
        // A stored value with no chip to match it would show the reader a row of
        // options none of which is selected, and no way back to what they have.
        assertTrue(
            "the default time is not among the ones offered",
            AppSettingsEntity().reminderMinuteOfDay in Reminders.TIME_OPTIONS,
        )
    }

    @Test
    fun `the times cover a morning reader as well as an evening one`() {
        assertTrue(
            "nothing on offer before noon",
            Reminders.TIME_OPTIONS.any { it < 12 * 60 },
        )
        assertTrue(
            "nothing on offer late enough for someone who reads in bed",
            Reminders.TIME_OPTIONS.any { it >= 21 * 60 },
        )
    }

    @Test
    fun `the wait is never nothing and never more than a day`() {
        // Zero would enqueue work that runs immediately, which turns a scheduled
        // reminder into one that fires the moment anything reschedules it.
        (0 until day).forEach { now ->
            listOf(0, 7 * 60, 20 * 60, day - 1).forEach { target ->
                val delay = Reminders.minutesUntil(now, target)
                assertTrue("a wait of $delay from $now to $target", delay in 1..day)
            }
        }
    }
}
