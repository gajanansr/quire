package app.folio.android.notify

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
