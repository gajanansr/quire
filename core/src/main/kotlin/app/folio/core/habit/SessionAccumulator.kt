package app.folio.core.habit

import app.folio.core.FolioConstants

/** A stretch of actual reading, in epoch milliseconds. */
data class ReadingInterval(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/** Minutes credited to one local day. */
data class DayMinutes(val epochDay: Long, val minutes: Int)

/**
 * Turns reading activity into minutes per day.
 *
 * Two properties matter more than precision here, and both come from the brief's
 * insistence that sessions track *reading* rather than an open app:
 *
 *  - **Idle time is excluded.** A phone left face-up on a page for an hour is not an
 *    hour of reading, and crediting it would inflate every streak and goal quietly.
 *    Activity — a page turn — extends the interval; silence beyond
 *    [FolioConstants.IDLE_TIMEOUT_MINUTES] ends it.
 *  - **Midnight splits.** Reading from 23:40 to 00:20 is twenty minutes on each of
 *    two days, not forty on either. Getting this wrong hands out a free streak day
 *    or loses a real one.
 */
class SessionAccumulator(
    /** Local midnight for a timestamp, as epoch millis. Injected so it is testable. */
    private val startOfDayMs: (Long) -> Long,
    /** The local epoch-day a timestamp falls in. */
    private val epochDayOf: (Long) -> Long,
) {

    private val idleMs = FolioConstants.IDLE_TIMEOUT_MINUTES * 60_000L

    /**
     * Collapses a stream of activity timestamps into reading intervals.
     *
     * [activity] is every moment the reader did something — opening the book,
     * turning a page — in order. A gap longer than the idle timeout ends the
     * interval at the last activity plus the timeout, not at the next one: the
     * reader plausibly kept reading for a while, but not for the whole gap.
     */
    fun intervals(activity: List<Long>, endedAtMs: Long?): List<ReadingInterval> {
        if (activity.isEmpty()) return emptyList()
        val times = activity.sorted()

        val out = mutableListOf<ReadingInterval>()
        var start = times.first()
        var last = times.first()

        for (i in 1 until times.size) {
            val t = times[i]
            if (t - last > idleMs) {
                out += ReadingInterval(start, last + idleMs)
                start = t
            }
            last = t
        }

        // The final stretch closes at the explicit end (backgrounding, leaving the
        // Reader) or, failing that, at the idle cutoff after the last activity.
        val close = endedAtMs?.coerceAtLeast(last) ?: (last + idleMs)
        out += ReadingInterval(start, minOf(close, last + idleMs))

        return out.filter { it.durationMs > 0 }
    }

    /** Splits intervals at local midnight and totals whole minutes per day. */
    fun creditDays(intervals: List<ReadingInterval>): List<DayMinutes> {
        val perDay = mutableMapOf<Long, Long>()

        intervals.forEach { interval ->
            var cursor = interval.startMs
            while (cursor < interval.endMs) {
                val dayStart = startOfDayMs(cursor)
                val nextMidnight = dayStart + DAY_MS
                val chunkEnd = minOf(interval.endMs, nextMidnight)
                val day = epochDayOf(cursor)
                perDay[day] = (perDay[day] ?: 0L) + (chunkEnd - cursor)
                cursor = chunkEnd
            }
        }

        return perDay
            .map { (day, ms) -> DayMinutes(day, (ms / 60_000L).toInt()) }
            // A stretch shorter than a minute rounds to zero and is not recorded:
            // "1 minute today" from a five-second glance would be a small lie.
            .filter { it.minutes > 0 }
            .sortedBy { it.epochDay }
    }

    fun creditFor(activity: List<Long>, endedAtMs: Long?): List<DayMinutes> =
        creditDays(intervals(activity, endedAtMs))

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
