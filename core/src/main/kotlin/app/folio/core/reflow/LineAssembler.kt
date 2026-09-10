package app.folio.core.reflow

import app.folio.core.source.PdfPage
import app.folio.core.source.TextRun
import kotlin.math.abs

/**
 * Groups a page's text runs into visual lines.
 *
 * PDF content streams carry no notion of a line: a run is emitted wherever the
 * producer happened to change position, font, or spacing, so a single visual line
 * routinely arrives as several runs and the reading order is not guaranteed.
 * Everything downstream — paragraph assembly, header detection, de-hyphenation —
 * reasons about lines, so this is where they are reconstructed.
 *
 * Runs are clustered by baseline within a tolerance derived from the type size on
 * the page, which keeps kerning and inline font changes on one line while a real
 * line advance starts a new one.
 *
 * This step deliberately does **not** split columns. Two runs at the same baseline
 * on opposite sides of a gutter stay in one line here; deciding that a gutter exists
 * needs evidence from the whole document, which is [ColumnDetector]'s job.
 */
class LineAssembler {

    private companion object {
        /** Fraction of glyph height within which two baselines count as the same line. */
        const val BASELINE_TOLERANCE = 0.45f
        const val FALLBACK_TOLERANCE = 2.0f
    }

    /**
     * Assembles lines honouring a detected column layout: runs are partitioned by
     * column first, each column is assembled independently, and the columns are
     * concatenated left to right. The result reads down one column and then the
     * next, which is the order a person reads them in.
     */
    fun assemble(page: PdfPage, layout: ColumnLayout): List<Line> = when (layout) {
        is ColumnLayout.Single -> assemble(page)
        is ColumnLayout.Multi -> {
            val bounds = listOf(Float.NEGATIVE_INFINITY) +
                layout.boundaries.sorted() + listOf(Float.POSITIVE_INFINITY)
            bounds.zipWithNext().flatMap { (from, to) ->
                // Assign a run to the column its horizontal centre falls in, so a run
                // that slightly overhangs the gutter does not jump columns.
                val slice = page.runs.filter { r ->
                    val centre = r.x + r.width / 2f
                    centre >= from && centre < to
                }
                if (slice.isEmpty()) emptyList() else assemble(page.copy(runs = slice))
            }
        }
    }

    fun assemble(page: PdfPage): List<Line> {
        val runs = page.runs.filter { it.text.isNotBlank() }
        if (runs.isEmpty()) return emptyList()

        val tolerance = toleranceFor(runs)

        // Group by descending baseline, folding each run into an open band when it
        // is close enough to that band's running mean.
        val bands = mutableListOf<MutableList<TextRun>>()
        val bandY = mutableListOf<Float>()

        runs.sortedByDescending { it.y }.forEach { run ->
            val i = bandY.indexOfFirst { abs(it - run.y) <= tolerance }
            if (i >= 0) {
                bands[i] += run
                bandY[i] = bands[i].sumOf { it.y.toDouble() }.toFloat() / bands[i].size
            } else {
                bands += mutableListOf(run)
                bandY += run.y
            }
        }

        return bands.mapIndexedNotNull { i, band -> toLine(band, bandY[i]) }
            .sortedByDescending { it.y }
    }

    /**
     * Tolerance scales with the page's typical glyph height so that a 24pt heading
     * and 9pt footnotes on the same page are both clustered sensibly.
     */
    private fun toleranceFor(runs: List<TextRun>): Float {
        val heights = runs.map { it.height }.filter { it > 0f }.sorted()
        val median = if (heights.isEmpty()) 0f else heights[heights.size / 2]
        return if (median > 0f) median * BASELINE_TOLERANCE else FALLBACK_TOLERANCE
    }

    private fun toLine(band: List<TextRun>, y: Float): Line? {
        val ordered = band.sortedBy { it.x }
        val text = joinWithSpacing(ordered)
        if (text.isBlank()) return null

        val sizes = ordered.map { it.fontSize }.sorted()
        val minX = ordered.minOf { it.x }
        val maxX = ordered.maxOf { it.x + it.width }

        return Line(
            text = text,
            x = minX,
            y = y,
            width = maxX - minX,
            height = ordered.maxOf { it.height },
            medianFontSize = sizes[sizes.size / 2],
            // A partly-bold line is not a heading candidate, so require all runs.
            bold = ordered.all { it.bold },
            runs = ordered,
        )
    }

    /**
     * Joins runs, inserting a space only where the producer left a visible gap and
     * neither side already supplies one. Without this, runs split mid-word rejoin
     * with a spurious space and words split across runs lose their separator.
     */
    private fun joinWithSpacing(ordered: List<TextRun>): String {
        val sb = StringBuilder()
        var prevRight: Float? = null
        var prevSize = 0f

        ordered.forEach { run ->
            val needsSpace = prevRight != null &&
                sb.isNotEmpty() &&
                !sb.last().isWhitespace() &&
                !run.text.first().isWhitespace() &&
                (run.x - prevRight!!) > prevSize * 0.2f
            if (needsSpace) sb.append(' ')
            sb.append(run.text)
            prevRight = run.x + run.width
            prevSize = run.fontSize
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
