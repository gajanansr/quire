package app.folio.core.reading

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Page counts and time remaining.
 *
 * These are **estimates**, and named so throughout. A reflowed book has no fixed
 * pages — the paginator decides them from the viewport and the reader's typography
 * — so a page number here is a nominal measure for the Book Details stat strip, not
 * a location. Positions are always character offsets (see `ReadingPosition`).
 *
 * Reading speed defaults to a conservative figure and is replaced by the reader's
 * own measured pace once enough sessions exist to compute one.
 */
object ReadingEstimates {

    /** Characters on a nominal page, roughly a paperback at typical settings. */
    const val CHARS_PER_PAGE = 1_800

    /** Average English word length plus its trailing space. */
    const val CHARS_PER_WORD = 5.7

    /** Conservative default until the reader's own pace is known. */
    const val DEFAULT_WORDS_PER_MINUTE = 220.0

    /** Below this many recorded minutes, a measured pace is too noisy to trust. */
    const val MIN_MINUTES_FOR_MEASURED_PACE = 10.0

    fun pageCount(totalChars: Int): Int =
        if (totalChars <= 0) 0 else ceil(totalChars.toDouble() / CHARS_PER_PAGE).toInt()

    fun currentPage(totalChars: Int, progress: Double): Int {
        if (totalChars <= 0) return 0
        val pages = pageCount(totalChars)
        val page = ceil(pages * progress.coerceIn(0.0, 1.0)).toInt()
        return page.coerceIn(1, pages)
    }

    fun pagesRemaining(totalChars: Int, progress: Double): Int =
        (pageCount(totalChars) - currentPage(totalChars, progress)).coerceAtLeast(0)

    fun minutesRemaining(
        totalChars: Int,
        progress: Double,
        wordsPerMinute: Double = DEFAULT_WORDS_PER_MINUTE,
    ): Int {
        if (totalChars <= 0 || wordsPerMinute <= 0.0) return 0
        val remainingChars = totalChars * (1.0 - progress.coerceIn(0.0, 1.0))
        val words = remainingChars / CHARS_PER_WORD
        return ceil(words / wordsPerMinute).toInt().coerceAtLeast(0)
    }

    /**
     * A reader's measured pace, or null when there is too little evidence.
     *
     * Returning null rather than a number computed from thirty seconds of reading
     * matters: a wildly wrong "3 minutes left" is worse than the honest default.
     */
    fun measuredWordsPerMinute(charsRead: Int, minutesSpent: Double): Double? {
        if (minutesSpent < MIN_MINUTES_FOR_MEASURED_PACE || charsRead <= 0) return null
        val words = charsRead / CHARS_PER_WORD
        val wpm = words / minutesSpent
        // Reject implausible paces: a phone left open on a page, or a reader who
        // skimmed a whole book in a minute, should not redefine their speed.
        return wpm.takeIf { it in 60.0..1200.0 }
    }

    /** "3h 21m left", "12m left", or null when the book is finished. */
    fun timeLeftLabel(
        totalChars: Int,
        progress: Double,
        wordsPerMinute: Double = DEFAULT_WORDS_PER_MINUTE,
    ): String? {
        val minutes = minutesRemaining(totalChars, progress, wordsPerMinute)
        if (minutes <= 0) return null
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) "${hours}h ${rest}m left" else "${minutes}m left"
    }

    /**
     * The handoff's pace line: "At 20 min a day, you'll finish in about 10 days."
     *
     * Null when the book is finished or the goal is nonsensical, so the caller
     * shows nothing rather than "about 0 days".
     */
    fun paceSentence(
        totalChars: Int,
        progress: Double,
        dailyGoalMinutes: Int,
        wordsPerMinute: Double = DEFAULT_WORDS_PER_MINUTE,
    ): String? {
        if (dailyGoalMinutes <= 0) return null
        val minutes = minutesRemaining(totalChars, progress, wordsPerMinute)
        if (minutes <= 0) return null
        val days = ceil(minutes.toDouble() / dailyGoalMinutes).roundToInt().coerceAtLeast(1)
        val dayWord = if (days == 1) "day" else "days"
        return "At $dailyGoalMinutes min a day, you'll finish in about $days $dayWord."
    }
}
