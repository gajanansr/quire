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
 * A live run of reading, and what it had to carry to stay alive.
 *
 * Two numbers rather than one because they answer different questions and only one
 * of them is the streak. [daysRead] is days the reader met their goal — nothing
 * else is ever counted into it. [restDays] are the days the run spanned without
 * reading, reported so the streak screen and the heatmap can show them rather than
 * quietly absorb them.
 */
data class StreakRun(
    val daysRead: Int,
    /** Days spanned without reading, newest first. Never counted in [daysRead]. */
    val restDays: List<Long>,
    /** The first day of the run, or null when there is no run. */
    val startDay: Long?,
)

/**
 * Streak arithmetic.
 *
 * The brief forbids fake streak calculations, so every rule here is one a reader
 * could check against their own history.
 *
 * The run is *forgiving*: a single missed day does not end it. What keeps that from
 * becoming flattery is that forgiveness is bounded and the number is not inflated by
 * it. At most one missed day is spanned in any [REST_EVERY_DAYS], and the figure the
 * reader sees counts days they actually read — a run of thirty that carried two rest
 * days says thirty, not thirty-two. A streak that silently repairs itself would be a
 * lie about what the reader did, and a lie is not forgiveness.
 *
 * There is nothing to earn, spend, equip or lose. The allowance is a property of the
 * calendar rather than an object the reader owns, which is the whole difference
 * between this and a mechanic that makes missing a day feel expensive.
 */
object Streaks {

    /**
     * How far apart two spanned days must be.
     *
     * One in seven. Lower and a reader who reads every other day accumulates an
     * endless streak, which is the dishonest failure in the other direction —
     * someone reading three times a week is not on a hundred-day run and should not
     * be told they are. It also means two missed days in a row always end a run,
     * which is the "never miss twice" rule the design is built on.
     */
    const val REST_EVERY_DAYS = 7

    /**
     * The run standing today: days read, and the days it spanned to get here.
     *
     * [restEveryDays] of 0 turns forgiveness off entirely, which is what [current]
     * is — one algorithm with a switch rather than two that drift apart.
     */
    fun run(
        days: List<ReadingDay>,
        today: Long,
        restEveryDays: Int = REST_EVERY_DAYS,
    ): StreakRun {
        val met = days.filter { it.metGoal }.map { it.epochDay }.toSet()
        if (met.isEmpty()) return StreakRun(0, emptyList(), null)
        val oldest = met.min()

        var daysRead = 0
        var startDay: Long? = null
        val rests = mutableListOf<Long>()
        var day = today

        while (day >= oldest) {
            when {
                day in met -> {
                    daysRead++
                    startDay = day
                }

                // Today with nothing read yet is not a missed day — the day is not
                // over. Spending the week's rest day on the hours before breakfast
                // would burn one every morning for a reader who reads each evening.
                day == today -> Unit

                else -> {
                    // Spanned only if the last day spanned is far enough away. The
                    // comparison is against the previous rest rather than a counter,
                    // so there is no allowance to accumulate and none to lose.
                    val previous = rests.lastOrNull()
                    val allowed = restEveryDays > 0 &&
                        (previous == null || previous - day >= restEveryDays)
                    if (!allowed) break
                    rests += day
                }
            }
            day--
        }

        // A rest day older than every day actually read leads nowhere: it would be
        // forgiveness granted for a run that had not started yet, and a mark on the
        // heatmap for a day the run never covered.
        val start = startDay
        return StreakRun(
            daysRead = daysRead,
            restDays = if (start == null) emptyList() else rests.filter { it > start },
            startDay = start,
        )
    }

    /**
     * Consecutive days meeting the goal, counting back from today or yesterday.
     *
     * The strict count, kept because some claims need it: a milestone that says the
     * reader read *every* day for a week cannot be satisfied by a week that had a
     * rest day in it.
     *
     * Allowing the run to end *yesterday* matters: at 00:01 a reader has not yet
     * had a chance to read today, and zeroing their streak the instant midnight
     * passes would be both wrong and discouraging. It breaks only once a full day
     * has gone by without reading.
     */
    fun current(days: List<ReadingDay>, today: Long): Int =
        run(days, today, restEveryDays = 0).daysRead

    /**
     * The longest run ever achieved, which survives the current one ending.
     *
     * Forgiving on exactly the terms [run] is, so the two numbers on the streak
     * screen are measured the same way. A reader comparing "now" against "best"
     * where the two used different rules would be comparing nothing.
     */
    fun longestRun(
        days: List<ReadingDay>,
        restEveryDays: Int = REST_EVERY_DAYS,
    ): Int {
        val met = days.filter { it.metGoal }.map { it.epochDay }.distinct().sorted()
        if (met.isEmpty()) return 0

        var best = 1
        var run = 1
        var lastRest: Long? = null

        for (i in 1 until met.size) {
            val gap = met[i] - met[i - 1]
            val rest = met[i - 1] + 1
            // Only a gap of exactly one day can be spanned. Two missed days in a row
            // would be two rests one day apart, which the window forbids anyway —
            // stating it here keeps the arithmetic readable.
            val spans = gap == 2L && restEveryDays > 0 &&
                (lastRest == null || rest - lastRest >= restEveryDays)

            when {
                gap == 1L -> run++
                spans -> { run++; lastRest = rest }
                else -> { run = 1; lastRest = null }
            }
            if (run > best) best = run
        }
        return best
    }

    /** The longest unbroken run — no rest days spanned at all. */
    fun longest(days: List<ReadingDay>): Int = longestRun(days, restEveryDays = 0)

    /** The last [count] days ending today, for the heatmap — including empty ones. */
    fun window(days: List<ReadingDay>, today: Long, count: Int): List<ReadingDay> {
        val byDay = days.associateBy { it.epochDay }
        return ((today - count + 1)..today).map { day ->
            byDay[day] ?: ReadingDay(day, minutes = 0, goalMinutes = 0)
        }
    }
}

/**
 * The daily goal.
 *
 * Was four hard-coded numbers cycled by tapping a row, which meant a reader who
 * wanted fifteen minutes could not have it. The presets survive as quick options —
 * most people do want "10 min" in one tap — and any minute count between [MIN] and
 * [MAX] is now a goal a reader can set.
 */
object Goals {

    /**
     * One minute.
     *
     * Not zero: [ReadingDay.metGoal] is false whenever the goal is zero, so a zero
     * goal is a streak that can never start and a heatmap that stays grey however
     * much the reader reads. One minute is also the smallest total the session
     * accumulator ever records, so it is the smallest goal a day can actually meet.
     */
    const val MIN = 1

    /**
     * Two hours.
     *
     * The product's opinion, stated once. A daily goal here is a floor to clear
     * rather than a target to fail, and a goal nobody keeps is a streak that never
     * starts — the opposite of what this app is for. Reading past the goal has
     * always counted, so the ceiling costs a heavy reader nothing.
     */
    const val MAX = 120

    /** The handoff's four, still one tap away. */
    val PRESETS = listOf(5, 10, 20, 30)

    const val RECOMMENDED = 5

    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN, MAX)

    /**
     * How much one tap moves the goal.
     *
     * A minute at a time around ten, where a minute is a tenth of the whole goal;
     * five at a time past half an hour, where it is not, and where counting to two
     * hours one minute per tap would be ninety taps.
     */
    fun step(minutes: Int): Int = if (minutes < 30) 1 else 5

    fun increase(minutes: Int): Int = clamp(minutes + step(minutes))

    /**
     * One tap down.
     *
     * The step is taken from the value *below* the current one so that up and back
     * return to where they started: stepping up from 29 reaches 30, and stepping
     * down from 30 has to reach 29 rather than 25, or the two buttons disagree and
     * a reader who overshoots by one tap can never get back.
     */
    fun decrease(minutes: Int): Int = clamp(minutes - step(minutes - 1))
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
        // Worded as a run of days read rather than as consecutive calendar days.
        // A run may now span a rest day, and "you read every day for a week" would
        // be false for a reader whose seven-day run carried one — the exact shape of
        // warm, specific and wrong the copy rules exist to prevent.
        Milestone("three_days", "Three Days", "A run of three days of reading."),
        Milestone("one_week", "Seven Days", "A run of seven days of reading."),
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
