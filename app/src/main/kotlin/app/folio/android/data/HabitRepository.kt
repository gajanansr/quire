package app.folio.android.data

import app.folio.core.habit.DayMinutes
import app.folio.core.habit.HabitStats
import app.folio.core.habit.Level
import app.folio.core.habit.Levels
import app.folio.core.habit.Milestone
import app.folio.core.habit.Milestones
import app.folio.core.habit.ReadingDay
import app.folio.core.habit.Streaks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
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
    private val db: FolioDatabase,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    fun todayEpochDay(): Long =
        LocalDate.now(zone()).toEpochDay()

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
    }

    suspend fun settings(): AppSettingsEntity =
        db.settings().get() ?: AppSettingsEntity().also { db.settings().put(it) }

    fun observeSettings(): Flow<AppSettingsEntity> =
        db.settings().observe().map { it ?: AppSettingsEntity() }

    suspend fun setDailyGoal(minutes: Int) {
        require(minutes in GOAL_OPTIONS) { "unsupported goal: $minutes" }
        db.settings().put(settings().copy(dailyGoalMinutes = minutes, onboarded = true))
    }

    suspend fun setTheme(name: String) {
        db.settings().put(settings().copy(themeName = name))
    }

    suspend fun setReaderPreferences(font: String, sizeSp: Float, justify: Boolean) {
        db.settings().put(
            settings().copy(
                readerFont = font, readerFontSizeSp = sizeSp, readerJustify = justify,
            )
        )
    }

    suspend fun recordBookFinished() {
        val current = settings()
        db.settings().put(current.copy(booksFinished = current.booksFinished + 1))
    }

    suspend fun recordChapterFinished() {
        val current = settings()
        db.settings().put(current.copy(chaptersFinished = current.chaptersFinished + 1))
    }

    companion object {
        /** The handoff's four options. */
        val GOAL_OPTIONS = listOf(5, 10, 20, 30)
        const val RECOMMENDED_GOAL = 5
        const val DEFAULT_GOAL = 10
    }
}
