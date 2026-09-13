package app.quire.android.data

import app.quire.core.habit.DayMinutes
import app.quire.core.habit.HabitStats
import app.quire.core.habit.Level
import app.quire.core.habit.Levels
import app.quire.core.habit.Milestone
import app.quire.core.habit.Milestones
import app.quire.core.habit.ReadingDay
import app.quire.core.habit.Streaks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

/** Everything the habit screens and the Library card need, computed from real data. */
data class HabitSummary(
    val days: List<ReadingDay> = emptyList(),
    val today: Long = 0,
    val goalMinutes: Int = 10,
    val minutesToday: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val xp: Int = 0,
    val level: Level = Levels.all.first(),
    val levelProgress: Double = 0.0,
    val nextLevel: Level? = null,
    val achieved: List<Milestone> = emptyList(),
    val locked: List<Milestone> = Milestones.all,
) {
    val goalMet: Boolean get() = goalMinutes > 0 && minutesToday >= goalMinutes

    /** The last seven days, oldest first, for the Library card's strip. */
    fun week(): List<ReadingDay> = Streaks.window(days, today, count = 7)

    /** Four weeks, for the streak heatmap. */
    fun month(): List<ReadingDay> = Streaks.window(days, today, count = 28)
}

/**
 * Reading habits, derived from recorded days rather than stored as totals.
 *
 * Streaks and levels are computed on read by the pure functions in `:core`. Caching
 * them would mean two sources of truth for the same number, and the one the reader
 * sees would eventually be the stale one.
 */
class HabitRepository(
    private val db: QuireDatabase,
    /**
     * Called after any write that changes what a home-screen widget shows.
     *
     * A callback rather than a context this class holds: the repository has no
     * business knowing widgets exist, and a test can count the calls. It sits ahead
     * of the clock parameters deliberately — those are the ones passed as trailing
     * lambdas, and a callback in the last position would silently swallow one.
     */
    private val onDataChanged: () -> Unit = {},
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Today, derived from the injected clock rather than the system one.
     *
     * These must agree with whatever records the minutes. Reading the wall clock
     * here while a caller supplies its own would let "today" mean two different
     * days in the same operation — minutes filed against one and the streak
     * checked against another.
     */
    fun todayEpochDay(): Long =
        Instant.ofEpochMilli(nowMs()).atZone(zone()).toLocalDate().toEpochDay()

    fun observeSummary(): Flow<HabitSummary> =
        combine(db.habits().observeDays(), db.settings().observe()) { days, settings ->
            val goal = settings?.dailyGoalMinutes ?: DEFAULT_GOAL
            val today = todayEpochDay()

            val history = days.map { ReadingDay(it.epochDay, it.minutes, it.goalMinutes) }
            val minutesToday = history.firstOrNull { it.epochDay == today }?.minutes ?: 0
            val totalMinutes = history.sumOf { it.minutes }
            val booksFinished = settings?.booksFinished ?: 0

            val xp = Levels.xpFor(totalMinutes, booksFinished)
            val stats = HabitStats(
                booksFinished = booksFinished,
                chaptersFinished = settings?.chaptersFinished ?: 0,
                totalMinutes = totalMinutes,
                currentStreak = Streaks.current(history, today),
                longestStreak = Streaks.longest(history),
                daysRead = history.count { it.minutes > 0 },
            )

            HabitSummary(
                days = history,
                today = today,
                goalMinutes = goal,
                minutesToday = minutesToday,
                currentStreak = stats.currentStreak,
                longestStreak = stats.longestStreak,
                xp = xp,
                level = Levels.levelFor(xp),
                levelProgress = Levels.progressWithinLevel(xp),
                nextLevel = Levels.nextLevel(xp),
                achieved = Milestones.achieved(stats),
                locked = Milestones.locked(stats),
            )
        }

    /**
     * Adds minutes to their days.
     *
     * Additive rather than replacing: a day accumulates across every session in it,
     * and a second session must not overwrite the first. The goal recorded is the
     * one in force now, so changing the goal later does not rewrite whether past
     * days were met.
     */
    suspend fun record(credits: List<DayMinutes>) {
        if (credits.isEmpty()) return
        val goal = settings().dailyGoalMinutes
        credits.forEach { credit ->
            val existing = db.habits().day(credit.epochDay)
            db.habits().put(
                ReadingDayEntity(
                    epochDay = credit.epochDay,
                    minutes = (existing?.minutes ?: 0) + credit.minutes,
                    goalMinutes = existing?.goalMinutes ?: goal,
                )
            )
        }
        // After the writes, not before: a refresh that races the transaction reads
        // the old row and looks exactly like a widget that did not update.
        onDataChanged()
    }

    suspend fun settings(): AppSettingsEntity =
        db.settings().get() ?: AppSettingsEntity().also { db.settings().put(it) }

    fun observeSettings(): Flow<AppSettingsEntity> =
        db.settings().observe().map { it ?: AppSettingsEntity() }

    suspend fun setDailyGoal(minutes: Int) {
        require(minutes in GOAL_OPTIONS) { "unsupported goal: $minutes" }
        db.settings().put(settings().copy(dailyGoalMinutes = minutes, onboarded = true))
        // The habit widget prints the goal. A goal changed in Settings and left
        // stale on the home screen is two answers to one question on one phone.
        onDataChanged()
    }

    suspend fun setTheme(name: String) {
        db.settings().put(settings().copy(themeName = name))
        // The widgets are painted in the reader's theme, so a theme change is a
        // change to what they show. Without this the home screen keeps the old
        // palette until the next write or the half-hour tick — the app turns dark
        // and the widget beside it stays on paper, which reads as a bug rather than
        // as a delay.
        onDataChanged()
    }

    suspend fun setReaderPreferences(font: String, sizeSp: Float, justify: Boolean) {
        db.settings().put(
            settings().copy(
                readerFont = font, readerFontSizeSp = sizeSp, readerJustify = justify,
            )
        )
    }

    // ------------------------------------------------------------- reminders

    /**
     * The master switch.
     *
     * Turning it off also clears the record of the last reminder sent. Without that,
     * a reader who switched reminders off in the evening and back on the next
     * morning would be silenced for a day by a fact about a notification they had
     * already decided to stop receiving.
     */
    suspend fun setRemindersEnabled(enabled: Boolean) {
        val current = settings()
        db.settings().put(
            current.copy(
                remindersEnabled = enabled,
                lastReminderDay = if (enabled) current.lastReminderDay else -1L,
            )
        )
    }

    suspend fun setReminderTime(minuteOfDay: Int) {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "not a time of day: $minuteOfDay" }
        db.settings().put(settings().copy(reminderMinuteOfDay = minuteOfDay))
    }

    suspend fun setReminderKinds(daily: Boolean, streak: Boolean) {
        db.settings().put(
            settings().copy(dailyReminderEnabled = daily, streakReminderEnabled = streak)
        )
    }

    /** Records that the offer was made, whichever way the reader answered it. */
    suspend fun markRemindersAsked() {
        db.settings().put(settings().copy(remindersAsked = true))
    }

    /**
     * The reader refused the system prompt.
     *
     * Reminders are switched off at the same time: a toggle reading "on" while the
     * OS refuses to deliver is a lie the reader would only discover by not being
     * reminded.
     */
    suspend fun markReminderPermissionDenied() {
        db.settings().put(
            settings().copy(reminderPermissionDenied = true, remindersEnabled = false)
        )
    }

    suspend fun recordReminderSent(epochDay: Long) {
        db.settings().put(settings().copy(lastReminderDay = epochDay))
    }

    suspend fun recordBookFinished() {
        val current = settings()
        db.settings().put(current.copy(booksFinished = current.booksFinished + 1))
        onDataChanged()
    }

    suspend fun recordChapterFinished() {
        val current = settings()
        db.settings().put(current.copy(chaptersFinished = current.chaptersFinished + 1))
        onDataChanged()
    }

    companion object {
        /** The handoff's four options. */
        val GOAL_OPTIONS = listOf(5, 10, 20, 30)
        const val RECOMMENDED_GOAL = 5
        const val DEFAULT_GOAL = 10
        const val MINUTES_PER_DAY = 24 * 60
    }
}
