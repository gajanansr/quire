package app.folio.android.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule that decides whether Folio says anything at all.
 *
 * Nearly every assertion here is a *negative* — the notification that must not
 * arrive. Negatives are invisible on a device: nothing happening looks exactly like
 * nothing happening for the wrong reason, and the reader who notices is the one
 * whose phone buzzed on a day they had already read. That is why the decision is a
 * pure function and why these tests exist at all.
 *
 * Plain JUnit 4, no Robolectric: [Reminders] imports nothing from Android.
 */
class RemindersTest {

    private val today = 20_345L

    /** Facts that would notify, so every test names only what it changes. */
    private fun facts(
        remindersEnabled: Boolean = true,
        dailyEnabled: Boolean = true,
        streakEnabled: Boolean = true,
        canPost: Boolean = true,
        minutesToday: Int = 0,
        goalMinutes: Int = 10,
        currentStreak: Int = 0,
        lastReminderDay: Long = today - 4,
        minuteOfDay: Int = 20 * 60,
        reminderMinuteOfDay: Int = 20 * 60,
        minutesSinceLastPageTurn: Int? = null,
        book: BookInProgress? = null,
    ) = ReminderFacts(
        remindersEnabled = remindersEnabled,
        dailyEnabled = dailyEnabled,
        streakEnabled = streakEnabled,
        canPost = canPost,
        today = today,
        minutesToday = minutesToday,
        goalMinutes = goalMinutes,
        currentStreak = currentStreak,
        lastReminderDay = lastReminderDay,
        minuteOfDay = minuteOfDay,
        reminderMinuteOfDay = reminderMinuteOfDay,
        minutesSinceLastPageTurn = minutesSinceLastPageTurn,
        book = book,
    )

    private fun silence(f: ReminderFacts): Silence? =
        (Reminders.decide(f) as? ReminderDecision.Silent)?.reason

    private fun kind(f: ReminderFacts): ReminderKind? =
        (Reminders.decide(f) as? ReminderDecision.Notify)?.kind

    // ------------------------------------------------- the rule that matters

    @Test
    fun `a day the reader has already read is never interrupted`() {
        // The single most important rule. A reminder that arrives after someone has
        // read is pure noise, and it is the one that makes people switch the whole
        // feature off.
        assertEquals(
            "one minute of reading was not enough to earn silence",
            Silence.ALREADY_READ_TODAY,
            silence(facts(minutesToday = 1)),
        )
    }

    @Test
    fun `a partial day counts as read, not as room for a nudge`() {
        // Four minutes of a ten-minute goal is still reading. The only notification
        // that fits a partly-read day is one pointing out the shortfall, which is
        // exactly the nagging this feature exists to avoid — so there is none.
        assertEquals(
            Silence.ALREADY_READ_TODAY,
            silence(facts(minutesToday = 4, goalMinutes = 10)),
        )
    }

    @Test
    fun `meeting the goal exactly is still a day that was read`() {
        assertEquals(
            Silence.ALREADY_READ_TODAY,
            silence(facts(minutesToday = 10, goalMinutes = 10)),
        )
        assertEquals(
            Silence.ALREADY_READ_TODAY,
            silence(facts(minutesToday = 90, goalMinutes = 10)),
        )
    }

    @Test
    fun `a live streak does not override a day already read`() {
        // The streak nudge is about a day with no reading in it. Sending one to
        // someone who read this morning would be the same noise with warmer words.
        assertEquals(
            Silence.ALREADY_READ_TODAY,
            silence(facts(minutesToday = 25, currentStreak = 9)),
        )
    }

    @Test
    fun `a reader with the book open right now is not interrupted`() {
        // The subtle version of the same rule, and the one that would actually have
        // shipped. Minutes reach the day rollup only when a *session ends* — on
        // leaving the Reader, or on the app going to the background. Someone ten
        // minutes into their first session of the day therefore still has zero
        // recorded minutes, and every check above would wave the reminder through
        // while the phone is in their hands and the book is on the screen.
        //
        // The signal is the last page turn, which is written on every turn because
        // the reading position is saved there.
        assertEquals(
            Silence.READING_RIGHT_NOW,
            silence(facts(minutesSinceLastPageTurn = 0)),
        )
        assertEquals(
            Silence.READING_RIGHT_NOW,
            silence(facts(minutesSinceLastPageTurn = Reminders.ACTIVE_WITHIN_MINUTES - 1)),
        )
    }

    @Test
    fun `a book put down earlier is not mistaken for one in hand`() {
        assertEquals(
            ReminderKind.DAILY,
            kind(facts(minutesSinceLastPageTurn = Reminders.ACTIVE_WITHIN_MINUTES)),
        )
        assertEquals(ReminderKind.DAILY, kind(facts(minutesSinceLastPageTurn = 600)))
        assertEquals(
            "a reader who has never turned a page was treated as mid-chapter",
            ReminderKind.DAILY,
            kind(facts(minutesSinceLastPageTurn = null)),
        )
    }

    @Test
    fun `an unread day at the chosen time is worth exactly one reminder`() {
        assertEquals(ReminderKind.DAILY, kind(facts()))
    }

    // --------------------------------------------------------- off means off

    @Test
    fun `the master switch outranks everything, including a long streak`() {
        // Off has to win from the first line of the function. A stale job that
        // outlives the switch being flipped still reaches here, and this is the
        // clause that makes it harmless.
        assertEquals(
            "reminders fired after the reader switched them off",
            Silence.REMINDERS_OFF,
            silence(facts(remindersEnabled = false, currentStreak = 40)),
        )
    }

    @Test
    fun `nothing is attempted without permission to post`() {
        assertEquals(Silence.CANNOT_POST, silence(facts(canPost = false)))
    }

    // ------------------------------------------------------- one, and no more

    @Test
    fun `a reminder already sent today is not sent again`() {
        assertEquals(
            Silence.ALREADY_SENT_TODAY,
            silence(facts(lastReminderDay = today)),
        )
    }

    @Test
    fun `a reminder sent yesterday does not silence today`() {
        assertEquals(ReminderKind.DAILY, kind(facts(lastReminderDay = today - 1)))
    }

    @Test
    fun `a clock that has gone backwards still silences`() {
        // A device whose timezone or clock moves can hand us a "last sent" day in
        // the future. Comparing with >= rather than == means that is read as
        // "already done" instead of licensing a notification every single run.
        assertEquals(
            Silence.ALREADY_SENT_TODAY,
            silence(facts(lastReminderDay = today + 3)),
        )
    }

    @Test
    fun `a fresh install has never been reminded`() {
        assertEquals(ReminderKind.DAILY, kind(facts(lastReminderDay = -1L)))
    }

    // --------------------------------------------------------- which register

    @Test
    fun `three days running earns the streak words`() {
        assertEquals(ReminderKind.STREAK, kind(facts(currentStreak = Reminders.STREAK_MIN)))
    }

    @Test
    fun `two days is not yet a streak worth naming`() {
        // Below the threshold the reader is told about their book, not about a run
        // of two — which is a number nobody is attached to yet.
        assertEquals(ReminderKind.DAILY, kind(facts(currentStreak = 2)))
        assertEquals(ReminderKind.DAILY, kind(facts(currentStreak = 0)))
    }

    @Test
    fun `each toggle silences its own kind and only its own`() {
        assertEquals(
            "streak nudges off should leave the daily reminder alone",
            ReminderKind.DAILY,
            kind(facts(streakEnabled = false, currentStreak = 9)),
        )
        assertEquals(
            "the daily reminder off should leave streak nudges alone",
            ReminderKind.STREAK,
            kind(facts(dailyEnabled = false, currentStreak = 9)),
        )
        assertEquals(
            "the daily reminder off left nothing to say on a streakless day",
            Silence.NO_KIND_ENABLED,
            silence(facts(dailyEnabled = false, currentStreak = 0)),
        )
        assertEquals(
            Silence.NO_KIND_ENABLED,
            silence(facts(dailyEnabled = false, streakEnabled = false, currentStreak = 9)),
        )
    }

    // ---------------------------------------------------- the delivery window

    @Test
    fun `nothing arrives before the time the reader picked`() {
        assertEquals(
            Silence.OUTSIDE_WINDOW,
            silence(facts(minuteOfDay = 20 * 60 - 1, reminderMinuteOfDay = 20 * 60)),
        )
        assertEquals(
            ReminderKind.DAILY,
            kind(facts(minuteOfDay = 20 * 60, reminderMinuteOfDay = 20 * 60)),
        )
    }

    @Test
    fun `a reminder that has gone stale is dropped rather than delivered late`() {
        // WorkManager schedules approximately; a dozing phone can run this hours
        // after the time the reader chose. Three hours late is still "this evening";
        // beyond that the reminder has stopped being about today's reading.
        val target = 18 * 60
        assertEquals(
            ReminderKind.DAILY,
            kind(facts(
                minuteOfDay = target + Reminders.DELIVERY_WINDOW_MINUTES,
                reminderMinuteOfDay = target,
            )),
        )
        assertEquals(
            Silence.OUTSIDE_WINDOW,
            silence(facts(
                minuteOfDay = target + Reminders.DELIVERY_WINDOW_MINUTES + 1,
                reminderMinuteOfDay = target,
            )),
        )
    }

    @Test
    fun `a phone that wakes at two in the morning stays quiet`() {
        // The failure this prevents is concrete: the reader's phone is in Doze all
        // evening, the job finally runs in the small hours, and Folio buzzes beside
        // a sleeping person. There is no window arithmetic that makes that welcome.
        assertEquals(
            Silence.OUTSIDE_WINDOW,
            silence(facts(minuteOfDay = 2 * 60, reminderMinuteOfDay = 20 * 60)),
        )
    }

    @Test
    fun `a late reminder still has a window before midnight`() {
        // 23:00 plus three hours is tomorrow, and a window that ran past midnight
        // would either be empty or would deliver on the wrong day. Clamping keeps
        // the last hour of the day usable.
        assertEquals(
            ReminderKind.DAILY,
            kind(facts(minuteOfDay = 23 * 60 + 30, reminderMinuteOfDay = 23 * 60)),
        )
        assertTrue(
            "a reminder set for the last minute of the day can never be delivered",
            Reminders.inWindow(24 * 60 - 1, 24 * 60 - 1),
        )
    }

    @Test
    fun `every minute of the day is inside exactly one reminder's window`() {
        // Sanity on the arithmetic itself rather than on a handful of examples: the
        // window must never be empty, whatever time the reader picks.
        (0 until 24 * 60).forEach { target ->
            assertTrue(
                "a reminder set for minute $target has an empty window",
                Reminders.inWindow(target, target),
            )
        }
    }

    // -------------------------------------------------------------- ordering

    @Test
    fun `the reasons are reported in priority order`() {
        // Precedence is the part of a rule chain that rots silently when a clause is
        // added later. Each of these facts trips several rules at once, and the one
        // reported has to be the earlier one — otherwise "off" could be reported as
        // "outside the window" and a reader's decision would look like a timing
        // accident in the logs and in every test written afterwards.
        val everythingWrong = facts(
            remindersEnabled = false,
            canPost = false,
            minutesToday = 30,
            lastReminderDay = today,
            dailyEnabled = false,
            streakEnabled = false,
            minuteOfDay = 3 * 60,
            minutesSinceLastPageTurn = 0,
        )
        assertEquals(Silence.REMINDERS_OFF, silence(everythingWrong))
        assertEquals(
            Silence.CANNOT_POST,
            silence(everythingWrong.copy(remindersEnabled = true)),
        )
        assertEquals(
            Silence.ALREADY_READ_TODAY,
            silence(everythingWrong.copy(remindersEnabled = true, canPost = true)),
        )
        assertEquals(
            Silence.READING_RIGHT_NOW,
            silence(everythingWrong.copy(
                remindersEnabled = true, canPost = true, minutesToday = 0,
            )),
        )
        assertEquals(
            Silence.ALREADY_SENT_TODAY,
            silence(everythingWrong.copy(
                remindersEnabled = true, canPost = true, minutesToday = 0,
                minutesSinceLastPageTurn = null,
            )),
        )
        assertEquals(
            Silence.NO_KIND_ENABLED,
            silence(everythingWrong.copy(
                remindersEnabled = true, canPost = true, minutesToday = 0,
                minutesSinceLastPageTurn = null, lastReminderDay = today - 1,
            )),
        )
        assertEquals(
            Silence.OUTSIDE_WINDOW,
            silence(everythingWrong.copy(
                remindersEnabled = true, canPost = true, minutesToday = 0,
                minutesSinceLastPageTurn = null, lastReminderDay = today - 1,
                dailyEnabled = true,
            )),
        )
    }

    @Test
    fun `the only way to reach a notification is for every rule to pass`() {
        // Stated from the other side: flipping any single fact away from the
        // notifying set must silence it. A rule that stopped being consulted would
        // show up here rather than as a notification nobody can explain.
        val ready = facts(currentStreak = 5, book = BookInProgress("A Book", "Chapter 3", 3, 41))
        assertTrue(Reminders.decide(ready) is ReminderDecision.Notify)

        val breakers = listOf(
            "master switch" to ready.copy(remindersEnabled = false),
            "permission" to ready.copy(canPost = false),
            "read today" to ready.copy(minutesToday = 1),
            "reading right now" to ready.copy(minutesSinceLastPageTurn = 0),
            "already sent" to ready.copy(lastReminderDay = today),
            "both kinds off" to ready.copy(dailyEnabled = false, streakEnabled = false),
            "too early" to ready.copy(minuteOfDay = 0),
        )
        breakers.forEach { (name, broken) ->
            assertTrue(
                "$name no longer silences a reminder",
                Reminders.decide(broken) is ReminderDecision.Silent,
            )
        }
    }
}
