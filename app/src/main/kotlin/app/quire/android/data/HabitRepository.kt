package app.quire.android.data

import app.quire.core.habit.DayMinutes
import app.quire.core.habit.Goals
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
    /**
     * Days the current run spanned without reading, newest first.
     *
     * Carried into the summary rather than recomputed by the screens so that the
     * number, the sentence under it and the heatmap are all describing the same
     * run. Two screens deriving forgiveness separately would eventually disagree
     * about which day was rested, which is a small lie in a feature whose whole
     * claim is that it does not tell them.
     */
    val restDays: List<Long> = emptyList(),
    /** The first day of the current run, or null when there is no run. */
    val streakStart: Long? = null,
    /**
     * Every day the reader has ever read, in total.
     *
     * The number that never resets, and the honest one. A run can end; this cannot,
     * and it is what the streak screen shows a reader whose run has just gone to
     * zero — the reading is still there even when the chain is not.
     */
    val daysRead: Int = 0,
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
            // The forgiving run, not the strict one. `Streaks.current` is still
            // there and still strict; nothing the reader sees uses it any more.
            val run = Streaks.run(history, today)
            val stats = HabitStats(
                booksFinished = booksFinished,
                chaptersFinished = settings?.chaptersFinished ?: 0,
                totalMinutes = totalMinutes,
                currentStreak = run.daysRead,
                longestStreak = Streaks.longestRun(history),
                daysRead = history.count { it.minutes > 0 },
            )

            HabitSummary(
                days = history,
                today = today,
                goalMinutes = goal,
                minutesToday = minutesToday,
                currentStreak = stats.currentStreak,
                longestStreak = stats.longestStreak,
                restDays = run.restDays,
                streakStart = run.startDay,
                daysRead = stats.daysRead,
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

    /**
     * The daily goal, now any minute count rather than one of four.
     *
     * Clamped rather than rejected. The old `require` threw on anything outside the
     * four presets, and with a stepper and a free choice behind it a throw would be
     * an uncaught exception in a coroutine launched from a composable — a crash on a
     * tap. [Goals.clamp] is the last line of defence behind a UI that already cannot
     * produce a value outside the range.
     */
    suspend fun setDailyGoal(minutes: Int) {
        db.settings().put(
            settings().copy(dailyGoalMinutes = Goals.clamp(minutes), onboarded = true)
        )
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
        /** The handoff's four, kept as quick options beside the free choice. */
        val GOAL_OPTIONS = Goals.PRESETS
        const val RECOMMENDED_GOAL = Goals.RECOMMENDED
        const val DEFAULT_GOAL = 10
        const val MINUTES_PER_DAY = 24 * 60
    }
}
