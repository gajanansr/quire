package app.quire.core.reflow

import app.quire.core.QuireConstants
import app.quire.core.source.PdfPage
import kotlin.math.abs

sealed interface ColumnLayout {
    data object Single : ColumnLayout
    /** x positions of gutter centres, ascending. One boundary means two columns. */
    data class Multi(val boundaries: List<Float>) : ColumnLayout
}

/**
 * Decides whether a document is laid out in columns.
 *
 * Getting this wrong is expensive in one direction only. Treating a single-column
 * book as two columns shreds every paragraph; treating a two-column book as one
 * merges facing lines into nonsense. Both are bad, but the first happens silently
 * on ordinary books, so the detector is biased toward [ColumnLayout.Single]: a gutter
 * must be clean, centrally placed, and present on most pages before it is believed.
 *
 * Detection runs on raw runs rather than assembled lines, because [LineAssembler]
 * merges across a gutter by design and would hide the very gap being looked for.
 */
class ColumnDetector {

    private companion object {
        const val BIN_COUNT = 60
        /** Only look for a gutter in the middle of the page, never near the margins. */
        const val SEARCH_LOW = 0.30f
        const val SEARCH_HIGH = 0.70f
        /** A gutter must be at least this fraction of page width. */
        const val MIN_GUTTER_WIDTH = 0.035f
        /** Both sides of the gutter must carry at least this share of the page's text. */
        const val MIN_SIDE_SHARE = 0.25f
        /** Two pages agree if their gutters sit within this fraction of page width. */
        const val AGREEMENT_TOLERANCE = 0.06f
        /** Ignore pages with too little text to say anything about layout. */
        const val MIN_RUNS_PER_PAGE = 8
    }

    fun detect(pages: List<PdfPage>): ColumnLayout {
        val considered = pages.filter { it.runs.count { r -> r.text.isNotBlank() } >= MIN_RUNS_PER_PAGE }
        if (considered.isEmpty()) return ColumnLayout.Single

        val gutters = considered.map { gutterOf(it) }
        val found = gutters.filterNotNull()
        if (found.isEmpty()) return ColumnLayout.Single

        // Cluster the per-page gutters and keep the largest agreeing group.
        val width = considered.first().geometry.width
        val tolerance = width * AGREEMENT_TOLERANCE
        val best = found.map { candidate -> found.filter { abs(it - candidate) <= tolerance } }
            .maxByOrNull { it.size } ?: return ColumnLayout.Single

        val share = best.size.toDouble() / considered.size
        if (share < QuireConstants.COLUMN_CONSISTENCY) return ColumnLayout.Single

        return ColumnLayout.Multi(listOf(best.average().toFloat()))
    }

    /**
     * Finds the widest empty vertical band in the central region of one page,
     * requiring meaningful text on both sides of it.
     */
    private fun gutterOf(page: PdfPage): Float? {
        val width = page.geometry.width
        if (width <= 0f) return null

        val runs = page.runs.filter { it.text.isNotBlank() }
        if (runs.size < MIN_RUNS_PER_PAGE) return null

        // Mark every bin any run overlaps, so a gutter is a genuinely untouched band.
        val occupied = BooleanArray(BIN_COUNT)
        runs.forEach { r ->
            val from = ((r.x / width) * BIN_COUNT).toInt().coerceIn(0, BIN_COUNT - 1)
            val to = (((r.x + r.width) / width) * BIN_COUNT).toInt().coerceIn(0, BIN_COUNT - 1)
            for (b in from..to) occupied[b] = true
        }

        val low = (BIN_COUNT * SEARCH_LOW).toInt()
        val high = (BIN_COUNT * SEARCH_HIGH).toInt()
        val minBins = maxOf(1, (BIN_COUNT * MIN_GUTTER_WIDTH).toInt())

        var bestStart = -1
        var bestLen = 0
        var start = -1
        for (b in low..high) {
            if (!occupied[b]) {
                if (start < 0) start = b
                val len = b - start + 1
                if (len > bestLen) { bestLen = len; bestStart = start }
            } else {
                start = -1
            }
        }
        if (bestLen < minBins) return null

        val centreBin = bestStart + bestLen / 2f
        val gutterX = (centreBin / BIN_COUNT) * width

        // Reject a ragged right margin masquerading as a gutter: both sides must
        // actually carry text.
        val left = runs.count { it.x + it.width <= gutterX }
        val right = runs.count { it.x >= gutterX }
        val total = runs.size.toDouble()
        if (left / total < MIN_SIDE_SHARE || right / total < MIN_SIDE_SHARE) return null

        return gutterX
    }
}
