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

        /** A dropped capital is at most this many characters. */
        const val DROP_CAP_MAX_CHARS = 2
        /** And at least this much larger than the body type around it. */
        const val DROP_CAP_SIZE_RATIO = 1.8f
        /** And tall enough to stand beside this many lines. That is the real test. */
        const val DROP_CAP_MIN_LINES_SPANNED = 2
        /** How far a capital stands above its baseline, as a fraction of type size. */
        const val CAP_HEIGHT_RATIO = 0.7f
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
        val allRuns = page.runs.filter { it.text.isNotBlank() }
        if (allRuns.isEmpty()) return emptyList()

        // Where the engine grouped the runs into lines, use its grouping whole.
        // It decides from the text matrix, the drop threshold and the font's own
        // metrics, and it already puts a dropped capital on the line it opens —
        // verified on a real book, where the 65pt "P" and the 20pt "alm trees along
        // the Marriott pool" arrive on the same line. Holding the capital back to
        // place it geometrically took it out of a correct grouping and put it back
        // worse, which is the same mistake as re-deriving the lines in the first
        // place.
        if (allRuns.all { it.lineIndex >= 0 }) {
            return allRuns.groupBy { it.lineIndex }
                .mapNotNull { band -> toLine(band.value, dominantY(band.value)) }
                .sortedByDescending { it.y }
        }

        // No engine behind this source: cluster baselines, and reattach dropped
        // capitals by geometry, since nothing else will.
        val tolerance = toleranceFor(allRuns)
        val bodySize = bodyFontSize(allRuns)
        val (capCandidates, runs) = allRuns.partition { it.mayBeDropCap(bodySize) }

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

        val lines = bands.mapIndexedNotNull { i, band -> toLine(band, bandY[i]) }
            .sortedByDescending { it.y }

        return attachDropCaps(lines, capCandidates, tolerance)
    }

    /** Size of the type the page is mostly set in, counted by character. */
    private fun bodyFontSize(runs: List<TextRun>): Float {
        val sizes = runs.flatMap { run ->
            List(run.text.trim().length.coerceAtLeast(1)) { run.fontSize }
        }.sorted()
        return if (sizes.isEmpty()) 0f else sizes[sizes.size / 2]
    }

    /** Short enough and large enough to be a dropped capital, before geometry says. */
    private fun TextRun.mayBeDropCap(bodySize: Float): Boolean =
        bodySize > 0f &&
            text.trim().length <= DROP_CAP_MAX_CHARS &&
            fontSize >= bodySize * DROP_CAP_SIZE_RATIO

    /**
     * Puts a dropped capital back on the word it opens.
     *
     * The deciding evidence is height, not size or position: a letter that stands
     * beside two or more lines is a drop cap, and one that does not is an ordinary
     * large glyph — an initial, a list marker, a stray — which keeps its own line.
     *
     * The letter joins the *topmost* line it spans, because that is the line it
     * begins, and merges into that line's first run rather than arriving as one of
     * its own: in a reflowed reader the drop cap's size is a property of the printed
     * page, not of the sentence, and carrying it through would set one letter of the
     * paragraph three times too large.
     */
    private fun attachDropCaps(
        lines: List<Line>,
        candidates: List<TextRun>,
        tolerance: Float,
    ): List<Line> {
        if (candidates.isEmpty()) return lines

        val result = lines.toMutableList()
        candidates.sortedByDescending { it.y }.forEach { cap ->
            // Measured from the type size rather than the reported ink box: a
            // producer reports the glyph's own height, which for a capital is well
            // short of how far it stands above the baseline.
            val top = cap.y + maxOf(cap.height, cap.fontSize * CAP_HEIGHT_RATIO)
            val spanned = result.filter { it.y <= top + tolerance && it.y >= cap.y - tolerance }

            if (spanned.size < DROP_CAP_MIN_LINES_SPANNED) {
                // Not a drop cap: a large glyph that does not stand past its own
                // line. It goes back to the line it shares a baseline with, exactly
                // as if it had never been held out — a big initial in the middle of
                // a sentence is still part of that sentence.
                val home = result.indexOfFirst { abs(it.y - cap.y) <= tolerance }
                if (home >= 0) {
                    toLine(result[home].runs + cap, result[home].y)?.let { result[home] = it }
                } else {
                    toLine(listOf(cap), cap.y)?.let { result += it }
                }
                return@forEach
            }

            val opens = spanned.maxByOrNull { it.y } ?: return@forEach
            val index = result.indexOf(opens)
            val letter = cap.text.trim()
            val first = opens.runs.firstOrNull()
            result[index] = opens.copy(
                text = letter + opens.text.trimStart(),
                x = minOf(cap.x, opens.x),
                runs = if (first == null) opens.runs
                else listOf(first.copy(text = letter + first.text.trimStart())) +
                    opens.runs.drop(1),
            )
        }
        return result.sortedByDescending { it.y }
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

    /**
     * The baseline of the run carrying most of the line's characters.
     *
     * Not the mean: a line opened by a dropped capital has one run sitting three
     * lines lower, and averaging would place the line halfway between, where it
     * belongs to neither. Paragraph gaps and header detection both measure from
     * this, so it has to be the baseline the body text actually sits on.
     */
    private fun dominantY(band: List<TextRun>): Float =
        band.maxByOrNull { it.text.trim().length }?.y ?: band.first().y

    /**
     * Type size weighted by character, so one enormous glyph does not define a line.
     *
     * A dropped capital is a single 65pt letter on a line of 20pt prose. Taking the
     * plain median of two runs gives 65, and the line then reads as a heading to
     * everything downstream — the sentence would be printed as a chapter title.
     */
    private fun weightedMedianSize(band: List<TextRun>): Float {
        val sizes = band.flatMap { run ->
            List(run.text.trim().length.coerceAtLeast(1)) { run.fontSize }
        }.sorted()
        return if (sizes.isEmpty()) 0f else sizes[sizes.size / 2]
    }

    private fun toLine(band: List<TextRun>, y: Float): Line? {
        val ordered = band.sortedBy { it.x }
        val text = joinWithSpacing(ordered)
        if (text.isBlank()) return null

        val minX = ordered.minOf { it.x }
        val maxX = ordered.maxOf { it.x + it.width }

        return Line(
            text = text,
            x = minX,
            y = y,
            width = maxX - minX,
            // Weighted for the same reason as the size: a drop cap is 30pt tall and
            // the line it opens is not.
            height = ordered.maxByOrNull { it.text.trim().length }?.height
                ?: ordered.maxOf { it.height },
            medianFontSize = weightedMedianSize(ordered),
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
