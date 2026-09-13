package app.folio.android.notify

/**
 * Reading reminders: whether to say anything, and which register to say it in.
 *
 * Pure Kotlin, deliberately. Nothing here imports Android, because every rule in
 * this file is a *negative* — a notification that must not appear — and a negative
 * cannot be checked by running the app. Nothing happening looks identical to nothing
 * happening for the wrong reason. Stating the rules as a function is what makes them
 * assertable, and [app.folio.android.notify.ReminderWorker] is then thin enough to
 * be read at a glance.
 */

/** The two things Folio ever says, and the only two. */
enum class ReminderKind {
    /** At the time the reader picked, about the book they are in the middle of. */
    DAILY,

    /**
     * The same moment, warmer words, when a run of days is live.
     *
     * It replaces the daily line rather than arriving beside it. Two notifications
     * in one evening is how an app teaches someone to mute it.
     */
    STREAK,
}

/** Why nothing was said. Distinct values so a test can assert the reason. */
enum class Silence {
    REMINDERS_OFF,
    CANNOT_POST,
    ALREADY_READ_TODAY,
    ALREADY_SENT_TODAY,
    NO_KIND_ENABLED,
    OUTSIDE_WINDOW,
}

/**
 * The book the reader is part-way through, as the copy needs it.
 *
 * Everything here is read from storage. [chapterLabel] is always a true label —
 * the chapter's own title when it has one, "Chapter N" when it does not — because
 * a notification that names a chapter the reader cannot find is worse than one that
 * names none.
 */
data class BookInProgress(
    val title: String,
    val chapterLabel: String,
    val chapterNumber: Int,
    val percentRead: Int,
)

/**
 * Everything the decision is allowed to consider.
 *
 * No defaults on purpose. The caller assembles these from real storage, and a
 * defaulted field is a field that can be forgotten — which for [remindersEnabled]
 * or [minutesToday] would mean notifying someone who said no or who has already
 * read today.
 */
data class ReminderFacts(
    val remindersEnabled: Boolean,
    val dailyEnabled: Boolean,
    val streakEnabled: Boolean,
    /** Whether the OS will actually deliver: permission, and the channel unmuted. */
    val canPost: Boolean,
    val today: Long,
    val minutesToday: Int,
    val goalMinutes: Int,
    val currentStreak: Int,
    val lastReminderDay: Long,
    val minuteOfDay: Int,
    val reminderMinuteOfDay: Int,
    val book: BookInProgress?,
)

sealed interface ReminderDecision {
    data class Silent(val reason: Silence) : ReminderDecision
    data class Notify(val kind: ReminderKind) : ReminderDecision
}

object Reminders {

    /** Below this, a run of days is not a number anyone is attached to yet. */
    const val STREAK_MIN = 3

    /**
     * How late a reminder may still be delivered.
     *
     * WorkManager schedules approximately and a dozing phone can run a job hours
     * after its delay elapses. Three hours late is still the same evening; beyond
     * that the reminder has stopped being about today's reading, and a phone that
     * buzzes at two in the morning because it woke up then is the failure this
     * number exists to prevent.
     */
    const val DELIVERY_WINDOW_MINUTES = 180

    const val MINUTES_PER_DAY = 24 * 60

    /**
     * Whether Folio says anything right now, and in which register.
     *
     * The order of these clauses is part of the rule, not an implementation
     * detail — see the precedence test. Off is checked first so that a decision the
     * reader made outranks every other consideration, including a stale job that
     * outlived the switch being flipped.
     */
    fun decide(facts: ReminderFacts): ReminderDecision {
        if (!facts.remindersEnabled) return ReminderDecision.Silent(Silence.REMINDERS_OFF)
        if (!facts.canPost) return ReminderDecision.Silent(Silence.CANNOT_POST)

        // The rule that matters most. Any reading at all, not "goal met": four
        // minutes of a ten-minute goal is still a day the reader read, and the only
        // notification that fits a partly-read day is one pointing out the
        // shortfall — which is precisely the nagging this feature exists to avoid.
        if (facts.minutesToday > 0) return ReminderDecision.Silent(Silence.ALREADY_READ_TODAY)

        // `>=` rather than `==`: a device whose clock or timezone moves backwards
        // can report a last-sent day in the future, and equality would read that as
        // "not today" and license a notification on every single run.
        if (facts.lastReminderDay >= facts.today) {
            return ReminderDecision.Silent(Silence.ALREADY_SENT_TODAY)
        }

        val kind = kindFor(facts) ?: return ReminderDecision.Silent(Silence.NO_KIND_ENABLED)
        if (!inWindow(facts.minuteOfDay, facts.reminderMinuteOfDay)) {
            return ReminderDecision.Silent(Silence.OUTSIDE_WINDOW)
        }
        return ReminderDecision.Notify(kind)
    }

    /**
     * True while a reminder set for [target] may still be delivered.
     *
     * The end is clamped to the last minute of the day rather than wrapping. A
     * window that ran past midnight would deliver on a day whose reading has not
     * happened yet, and a reader who picks 11pm would otherwise get either nothing
     * or tomorrow's reminder tonight.
     */
    fun inWindow(minuteOfDay: Int, target: Int): Boolean {
        val end = (target + DELIVERY_WINDOW_MINUTES).coerceAtMost(MINUTES_PER_DAY - 1)
        return minuteOfDay in target..end
    }

    /**
     * Minutes to wait before the next reminder is due.
     *
     * Equal times mean tomorrow, deliberately: turning reminders on at exactly the
     * chosen minute must not buzz the phone in that same instant, and neither must
     * the worker rescheduling itself after a delivery. The result is therefore never
     * zero, which is also what stops a rescheduled job from running immediately and
     * again and again.
     */
    fun minutesUntil(nowMinuteOfDay: Int, targetMinuteOfDay: Int): Int {
        val ahead = targetMinuteOfDay - nowMinuteOfDay
        return if (ahead > 0) ahead else ahead + MINUTES_PER_DAY
    }

    /**
     * A time of day, written the way the reader's phone writes times.
     *
     * [use24Hour] comes from the system setting rather than from a locale guess, so
     * someone whose phone shows 20:00 is not shown 8:00 pm by the one screen in
     * Folio that talks about clock time.
     *
     * Midnight and noon are the whole reason this is a function and not a format
     * string: both are hour zero modulo twelve, and the obvious conversion writes
     * them as "0:00 am" and "0:00 pm".
     */
    fun formatTime(minuteOfDay: Int, use24Hour: Boolean): String {
        val hour = (minuteOfDay / 60).coerceIn(0, 23)
        val minute = (minuteOfDay % 60).coerceIn(0, 59)
        val paddedMinute = minute.toString().padStart(2, '0')
        if (use24Hour) return "${hour.toString().padStart(2, '0')}:$paddedMinute"
        val suffix = if (hour < 12) "am" else "pm"
        val twelve = when (hour % 12) {
            0 -> 12
            else -> hour % 12
        }
        return "$twelve:$paddedMinute $suffix"
    }

    /** Null when the reader has switched off every kind that applies today. */
    private fun kindFor(facts: ReminderFacts): ReminderKind? = when {
        facts.streakEnabled && facts.currentStreak >= STREAK_MIN -> ReminderKind.STREAK
        facts.dailyEnabled -> ReminderKind.DAILY
        else -> null
    }
}
