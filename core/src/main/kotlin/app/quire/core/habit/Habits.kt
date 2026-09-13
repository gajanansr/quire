package app.quire.core.habit

/**
 * One day's reading, keyed by local epoch-day.
 *
 * Local rather than UTC on purpose: a reader in Auckland finishing at 23:50 has
 * read *today*, and a UTC key would file it as tomorrow and hand them a streak
 * they did not earn — or break one they did.
 */
data class ReadingDay(
    val epochDay: Long,
    val minutes: Int,
    val goalMinutes: Int,
) {
    val metGoal: Boolean get() = goalMinutes > 0 && minutes >= goalMinutes
}

/**
 * Streak arithmetic.
 *
 * The brief forbids fake streak calculations, so every rule here is one a reader
 * could check against their own history.
 */
object Streaks {

    /**
     * Consecutive days meeting the goal, counting back from today or yesterday.
     *
     * Allowing the run to end *yesterday* matters: at 00:01 a reader has not yet
     * had a chance to read today, and zeroing their streak the instant midnight
     * passes would be both wrong and discouraging. It breaks only once a full day
     * has gone by without reading.
     */
    fun current(days: List<ReadingDay>, today: Long): Int {
        val met = days.filter { it.metGoal }.associateBy { it.epochDay }
        if (met.isEmpty()) return 0

        val start = when {
            met.containsKey(today) -> today
            met.containsKey(today - 1) -> today - 1
            else -> return 0
        }

        var count = 0
        var day = start
        while (met.containsKey(day)) {
            count++
            day--
        }
        return count
    }

    /** The longest run ever achieved, which survives the current one ending. */
    fun longest(days: List<ReadingDay>): Int {
        val met = days.filter { it.metGoal }.map { it.epochDay }.distinct().sorted()
        if (met.isEmpty()) return 0

        var best = 1
        var run = 1
        for (i in 1 until met.size) {
            run = if (met[i] == met[i - 1] + 1) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /** The last [count] days ending today, for the heatmap — including empty ones. */
    fun window(days: List<ReadingDay>, today: Long, count: Int): List<ReadingDay> {
        val byDay = days.associateBy { it.epochDay }
        return ((today - count + 1)..today).map { day ->
            byDay[day] ?: ReadingDay(day, minutes = 0, goalMinutes = 0)
        }
    }
}

data class Level(val index: Int, val name: String, val minXp: Int)

/**
 * Experience and levels.
 *
 * Deliberately shallow. The handoff's six levels reward showing up rather than
 * volume, and the brief warns against turning the Reader into a game — so minutes
 * read are worth a little and finishing a book is worth more, and nothing
 * compounds.
 */
object Levels {

    const val XP_PER_MINUTE = 1
    const val XP_PER_BOOK = 100

    /** The handoff's six levels. */
    val all = listOf(
        Level(0, "New Reader", 0),
        Level(1, "Curious", 150),
        Level(2, "Steady", 400),
        Level(3, "Devoted", 900),
        Level(4, "Regular Reader", 1_800),
        Level(5, "Lifelong Reader", 3_500),
    )

    fun xpFor(minutesRead: Int, booksFinished: Int): Int =
        (minutesRead.coerceAtLeast(0) * XP_PER_MINUTE) +
            (booksFinished.coerceAtLeast(0) * XP_PER_BOOK)

    fun levelFor(xp: Int): Level = all.last { xp >= it.minXp }

    /** Progress through the current level, 0..1. The top level sits at 1. */
    fun progressWithinLevel(xp: Int): Double {
        val level = levelFor(xp)
        val next = all.getOrNull(level.index + 1) ?: return 1.0
        val span = (next.minXp - level.minXp).toDouble()
        if (span <= 0) return 1.0
        return ((xp - level.minXp) / span).coerceIn(0.0, 1.0)
    }

    fun nextLevel(xp: Int): Level? = all.getOrNull(levelFor(xp).index + 1)
}

/** What the reader has done, as milestones are judged against it. */
data class HabitStats(
    val booksFinished: Int = 0,
    val chaptersFinished: Int = 0,
    val totalMinutes: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val daysRead: Int = 0,
)

data class Milestone(val id: String, val title: String, val detail: String)

/**
 * Milestones.
 *
 * No numeric badges, per the handoff — a milestone is a sentence about something
 * that happened, not a score. They are also all achievable by a reader who simply
 * reads, rather than by one who optimises.
 */
object Milestones {

    val all = listOf(
        Milestone("first_page", "First Page", "You opened a book."),
        Milestone("first_ten", "First 10 Minutes", "You read for ten minutes."),
        Milestone("first_chapter", "First Chapter", "You finished a chapter."),
        Milestone("first_book", "First Book", "You finished a book."),
        Milestone("three_days", "Three Days", "You read three days running."),
        Milestone("one_week", "A Full Week", "You read every day for a week."),
        Milestone("ten_hours", "Ten Hours", "You have read for ten hours."),
        Milestone("five_books", "Five Books", "You finished five books."),
    )

    fun achieved(stats: HabitStats): List<Milestone> = all.filter { milestone ->
        when (milestone.id) {
            "first_page" -> stats.totalMinutes > 0 || stats.daysRead > 0
            "first_ten" -> stats.totalMinutes >= 10
            "first_chapter" -> stats.chaptersFinished >= 1
            "first_book" -> stats.booksFinished >= 1
            "three_days" -> stats.longestStreak >= 3
            "one_week" -> stats.longestStreak >= 7
            "ten_hours" -> stats.totalMinutes >= 600
            "five_books" -> stats.booksFinished >= 5
            else -> false
        }
    }

    fun locked(stats: HabitStats): List<Milestone> = all - achieved(stats).toSet()
}
