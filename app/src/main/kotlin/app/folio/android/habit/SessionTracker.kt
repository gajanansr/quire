package app.folio.android.habit

import app.folio.android.data.HabitRepository
import app.folio.core.habit.DayMinutes
import app.folio.core.habit.SessionAccumulator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Records what a reader actually read.
 *
 * Collects a timestamp for every meaningful action — opening a book, turning a
 * page — and converts them into minutes when the session ends. The arithmetic
 * (idle exclusion, midnight splitting) lives in [SessionAccumulator]; this holds
 * the timeline and decides when to commit it.
 *
 * Flushing happens on leaving the Reader and on the app going to the background,
 * not only on a clean exit: most reading sessions end by someone locking their
 * phone, and a tracker that only commits on a back-press would lose almost all of
 * them.
 */
class SessionTracker(
    private val habits: HabitRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val accumulator = SessionAccumulator(
        startOfDayMs = { ms ->
            Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()
                .atStartOfDay(zone()).toInstant().toEpochMilli()
        },
        epochDayOf = { ms ->
            Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toEpochDay()
        },
    )

    private val activity = mutableListOf<Long>()

    val isActive: Boolean get() = activity.isNotEmpty()

    /** Called on opening a book and on every page turn. */
    fun record(at: Long = nowMs()) {
        activity += at
    }

    /**
     * Commits the session and clears it.
     *
     * Returns what was credited so a caller can react — showing the daily-goal
     * screen, for instance. Returns empty when there was nothing worth recording,
     * which is the common case for someone who opened a book and closed it again.
     */
    suspend fun flush(endedAt: Long = nowMs()): List<DayMinutes> {
        if (activity.isEmpty()) return emptyList()
        val credits = accumulator.creditFor(activity.toList(), endedAt)
        activity.clear()
        if (credits.isNotEmpty()) habits.record(credits)
        return credits
    }

    /** Minutes credited to today in a set of credits, for the goal check. */
    fun minutesToday(credits: List<DayMinutes>): Int {
        val today = LocalDate.now(zone()).toEpochDay()
        return credits.firstOrNull { it.epochDay == today }?.minutes ?: 0
    }
}
