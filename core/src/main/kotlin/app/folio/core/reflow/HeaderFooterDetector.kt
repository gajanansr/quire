package app.folio.core.reflow

import app.folio.core.FolioConstants
import kotlin.math.abs

data class StripResult(
    val pages: List<List<Line>>,
    val removedHeaders: Int,
    val removedFooters: Int,
    val confidence: Double,
)

/**
 * Removes running headers, running footers, and page numbers.
 *
 * This is the step most capable of destroying a book, so it is deliberately hard to
 * trigger. A line is only furniture when it satisfies every condition at once: it
 * sits in the top or bottom margin band, it recurs at a consistent height across at
 * least [FolioConstants.HEADER_RECURRENCE] of pages, and the document has enough
 * pages for recurrence to mean anything.
 *
 * Two failure modes drove the design. A repeated line in the middle of a page is
 * content — a refrain, a chapter epigraph, a table row — and is never touched. And a
 * one-page document has no recurrence to measure, so nothing is removed at all;
 * without that guard, "appears on 100% of pages" would delete every line.
 *
 * Headers that vary only by page number ("Chapter 3 — page 7") are normalized before
 * comparison so they still count as the same header.
 */
class HeaderFooterDetector {

    private companion object {
        /** Furniture must recur at roughly the same height. */
        const val Y_TOLERANCE = 0.02f
        /** Below this page count, recurrence is not evidence of anything. */
        const val MIN_PAGES = 3
        /** A line this long is prose, not a running head, however often it repeats. */
        const val MAX_FURNITURE_CHARS = 120
    }

    /**
     * @param pageHeights the real height of each page, in the same order as [pages].
     *   Page height must come from the document's geometry, never be inferred from
     *   the topmost line: on a page whose highest line *is* the running header, the
     *   inferred height collapses the margin band onto the body text.
     */
    fun strip(pages: List<List<Line>>, pageHeights: List<Float>): StripResult {
        require(pageHeights.size == pages.size) {
            "expected ${pages.size} page heights, got ${pageHeights.size}"
        }
        if (pages.size < MIN_PAGES) {
            return StripResult(pages, 0, 0, 0.5)
        }

        fun topBandOf(p: Int) = pageHeights[p] * (1f - FolioConstants.MARGIN_BAND)
        fun bottomBandOf(p: Int) = pageHeights[p] * FolioConstants.MARGIN_BAND
        val referenceHeight = pageHeights.max()

        // Candidates are edge-band lines only; everything else is content by definition.
        data class Candidate(val page: Int, val line: Line, val top: Boolean)

        val candidates = pages.flatMapIndexed { p, lines ->
            lines.mapNotNull { line ->
                when {
                    line.text.length > MAX_FURNITURE_CHARS -> null
                    line.y >= topBandOf(p) -> Candidate(p, line, top = true)
                    line.y <= bottomBandOf(p) -> Candidate(p, line, top = false)
                    else -> null
                }
            }
        }

        val threshold = pages.size * FolioConstants.HEADER_RECURRENCE
        val doomed = mutableSetOf<Pair<Int, Line>>()
        var headers = 0
        var footers = 0

        // Group by normalized text and edge, then require consistent vertical placement.
        candidates.groupBy { normalize(it.line.text) to it.top }
            .forEach { (key, group) ->
                val (_, isTop) = key
                val distinctPages = group.map { it.page }.distinct()
                if (distinctPages.size < threshold) return@forEach

                val medianY = group.map { it.line.y }.sorted()[group.size / 2]
                val aligned = group.filter {
                    abs(it.line.y - medianY) <= referenceHeight * Y_TOLERANCE
                }
                if (aligned.map { it.page }.distinct().size < threshold) return@forEach

                aligned.forEach { doomed += it.page to it.line }
                if (isTop) headers += aligned.size else footers += aligned.size
            }

        // Bare page numbers in a margin band are furniture regardless of recurrence,
        // since the text differs on every page by design.
        candidates.filter { isPageNumber(it.line.text) }.forEach {
            if ((it.page to it.line) !in doomed) {
                doomed += it.page to it.line
                if (it.top) headers++ else footers++
            }
        }

        val stripped = pages.mapIndexed { p, lines -> lines.filterNot { (p to it) in doomed } }
        val removed = headers + footers

        return StripResult(
            pages = stripped,
            removedHeaders = headers,
            removedFooters = footers,
            // Confidence reflects how cleanly furniture was identified, not how much
            // was removed. Finding none is a perfectly good outcome.
            confidence = if (removed == 0) 0.5 else (0.6 + 0.4 * (removed.toDouble() /
                (pages.size * 2).coerceAtLeast(1)).coerceAtMost(1.0)),
        )
    }

    /**
     * Collapses the parts of a running head that legitimately vary between pages —
     * page numbers and roman numerals — so the stable part can be matched.
     */
    private fun normalize(text: String): String = text
        .lowercase()
        .replace(Regex("\\d+"), "#")
        .replace(Regex("\\b[ivxlcdm]+\\b"), "#")
        .replace(Regex("[^a-z#]+"), " ")
        .trim()

    private fun isPageNumber(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 12) return false
        return t.matches(Regex("\\d{1,4}")) ||
            t.matches(Regex("[ivxlcdmIVXLCDM]{1,7}")) ||
            t.matches(Regex("[-–—]\\s*\\d{1,4}\\s*[-–—]")) ||
            t.matches(Regex("(?i)page\\s+\\d{1,4}"))
    }
}
