package app.quire.core.reflow

import app.quire.core.QuireConstants
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.model.InlineStyle
import kotlin.math.abs

/**
 * Joins lines into paragraphs and headings.
 *
 * A PDF has no paragraphs, only lines that happen to sit near each other, so the
 * breaks have to be inferred from three independent signals: a line that stops short
 * of the measure has ended its paragraph, a change of indent starts one, and a
 * vertical gap wider than the prevailing leading separates them.
 *
 * Headings are identified here rather than in [app.quire.core.structure.ChapterDetector]
 * because this is the last step that still has font metrics — once lines become
 * [ContentBlock]s, type size is gone. Promotion requires real typographic evidence:
 * a line noticeably larger than the body median, short, and standing alone. A book
 * set entirely in one size yields no headings at all, which is the correct answer
 * rather than a failure.
 */
class ParagraphAssembler {

    private companion object {
        /** How much larger than body type a line must be to read as a heading. */
        const val HEADING_SIZE_RATIO = 1.15f
        /** Headings are short; beyond this it is prose however it is set. */
        const val HEADING_MAX_CHARS = 90
        /** A line this much shorter than the measure has ended its paragraph. */
        const val SHORT_LINE_RATIO = 0.85f
        /** Indent changes below this many points are noise. */
        const val INDENT_TOLERANCE = 3f
        /** An indent at least this large starts a paragraph. */
        const val MIN_INDENT = 8f
        /** The share of lines the measure must sit at or below, ignoring outliers. */
        const val MEASURE_PERCENTILE = 0.9f
        /** Below this many lines, ranking says nothing and the widest line is used. */
        const val MIN_LINES_FOR_PERCENTILE = 4
        /** Fewer baseline gaps than this cannot establish a leading. */
        const val MIN_GAPS_FOR_EMPIRICAL = 3
        /** Leading is conventionally a little larger than the type size. */
        const val TYPICAL_LEADING_RATIO = 1.25f
        /** Beyond this multiple of type size, a gap is separation rather than leading. */
        const val MAX_LEADING_RATIO = 1.6f

        val SENTENCE_ENDINGS = setOf('.', '?', '!')

        /** Words a title does not end on, because a clause is still open after them. */
        val TRAILING_FUNCTION_WORDS = setOf(
            "a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "into",
            "is", "of", "on", "or", "the", "to", "was", "were", "with", "that",
            "said", "than", "then", "when", "while", "who", "which",
        )
    }

    fun assemble(lines: List<Line>): List<ContentBlock> {
        val body = lines.filter { it.text.isNotBlank() }
        if (body.isEmpty()) return emptyList()

        val bodySize = medianFontSize(body)
        val leading = estimateLeading(body, bodySize)
        val measure = measureOf(body)
        val baseIndent = body.map { it.x }.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: body.minOf { it.x }

        val blocks = mutableListOf<ContentBlock>()
        var current = mutableListOf<Line>()

        fun flush() {
            if (current.isEmpty()) return
            blocks += paragraphOf(current)
            current = mutableListOf()
        }

        body.forEachIndexed { i, line ->
            val prev = body.getOrNull(i - 1)

            if (isHeading(line, bodySize, prev, body.getOrNull(i + 1), leading)) {
                flush()
                blocks += ContentBlock.Heading(headingLevel(line, bodySize), spansOf(line))
                return@forEachIndexed
            }

            if (prev != null && breaksParagraph(prev, line, measure, leading, baseIndent)) flush()
            current += line
        }
        flush()

        return blocks
    }

    private fun breaksParagraph(
        prev: Line,
        line: Line,
        measure: Float,
        leading: Float,
        baseIndent: Float,
    ): Boolean {
        // The previous line stopped well short of the measure: it ended a paragraph.
        if (prev.width < measure * SHORT_LINE_RATIO) return true

        // A first-line indent relative to the body's usual left edge.
        val indented = line.x - baseIndent
        if (indented > MIN_INDENT && abs(indented) > INDENT_TOLERANCE) return true

        // A vertical gap wider than the prevailing leading.
        val gap = prev.y - line.y
        if (leading > 0f && gap > leading * QuireConstants.PARAGRAPH_GAP_FACTOR) return true

        return false
    }

    /**
     * A heading needs typographic evidence, not just brevity. Requiring a size step
     * means a short line in a uniformly set book stays prose, which is what the
     * conservatism rule asks for: no invented structure.
     */
    private fun isHeading(
        line: Line,
        bodySize: Float,
        prev: Line?,
        next: Line?,
        leading: Float,
    ): Boolean {
        if (line.text.length > HEADING_MAX_CHARS) return false
        val larger = line.medianFontSize >= bodySize * HEADING_SIZE_RATIO
        if (!larger) return false

        // A heading is a complete thing. Books often set a chapter's opening
        // sentence large as a flourish, and it is still a sentence — treating it as
        // a title splits it in half and prints the first part as a chapter heading.
        if (readsAsUnfinished(line, next)) return false

        // Standing alone: separated from at least one neighbour by more than a
        // normal line advance, or sitting at the very start of the page.
        val gapBefore = prev?.let { it.y - line.y } ?: Float.MAX_VALUE
        val gapAfter = next?.let { line.y - it.y } ?: Float.MAX_VALUE
        val isolated = leading <= 0f ||
            gapBefore > leading * QuireConstants.PARAGRAPH_GAP_FACTOR ||
            gapAfter > leading * QuireConstants.PARAGRAPH_GAP_FACTOR

        return isolated
    }

    /**
     * Whether a line is plainly the middle of a sentence.
     *
     * Three signs, any one of which is enough, and all of which only ever *prevent*
     * a heading — the safe direction, since inventing one costs more than missing
     * one. A line carrying a finished sentence and then more text is prose. A line
     * ending on a word that cannot end a sentence is prose. A line whose successor
     * opens in lower case is prose continuing.
     */
    private fun readsAsUnfinished(line: Line, next: Line?): Boolean {
        val text = line.text.trim()
        if (text.isEmpty()) return false

        val body = text.dropLast(1)
        if (body.any { it in SENTENCE_ENDINGS }) return true

        val lastWord = text.split(Regex("[^\\p{L}']+")).lastOrNull { it.isNotBlank() }
        if (lastWord != null && lastWord.lowercase() in TRAILING_FUNCTION_WORDS) return true

        val continues = next?.text?.trimStart()?.firstOrNull()
        return continues != null && continues.isLowerCase()
    }

    /** Bigger type means a higher-level heading, clamped to the h1..h4 the reader styles. */
    private fun headingLevel(line: Line, bodySize: Float): Int {
        if (bodySize <= 0f) return 2
        val ratio = line.medianFontSize / bodySize
        return when {
            ratio >= 1.8f -> 1
            ratio >= 1.45f -> 2
            ratio >= 1.25f -> 3
            else -> 4
        }
    }

    private fun paragraphOf(lines: List<Line>): ContentBlock =
        ContentBlock.Paragraph(spansOf(lines))

    private fun spansOf(line: Line): List<InlineSpan> = spansOf(listOf(line))

    /**
     * Joins a paragraph's lines with single spaces. A line already ending in a
     * hyphen has been through [Dehyphenator]; anything left is intentional.
     */
    private fun spansOf(lines: List<Line>): List<InlineSpan> {
        val text = lines.joinToString(" ") { it.text.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        val style = if (lines.all { it.bold }) setOf(InlineStyle.STRONG) else emptySet()
        return listOf(InlineSpan(text, style))
    }

    /**
     * The width a full line of this page reaches.
     *
     * Taking the widest line makes one line the sole authority on the page, and
     * every rule downstream is then only as sound as the worst width on it. A
     * dehyphenated line that reported the sum of its two halves was enough to put
     * the threshold above every real line, and since a line stopping short of the
     * measure ends a paragraph, the page came out one line per paragraph — a whole
     * book indented on every line.
     *
     * A high percentile says the same thing about a well-behaved page and survives
     * an outlier on a badly-behaved one, because full lines are the common case and
     * a freak width is not. Where there are too few lines to rank, the widest is
     * still the best available answer.
     */
    private fun measureOf(body: List<Line>): Float {
        val widths = body.map { it.width }.sorted()
        if (widths.size < MIN_LINES_FOR_PERCENTILE) return widths.last()
        val at = ((widths.size - 1) * MEASURE_PERCENTILE).toInt()
        return widths[at]
    }

    private fun medianFontSize(lines: List<Line>): Float {
        val sizes = lines.map { it.medianFontSize }.filter { it > 0f }.sorted()
        return if (sizes.isEmpty()) 0f else sizes[sizes.size / 2]
    }

    /**
     * The page's prevailing leading.
     *
     * Measuring it from the baselines is right when there are enough of them, but
     * degenerates badly when there are not: with two lines the only gap *is* the
     * median, so no gap can ever exceed it and paragraph breaks stop being detected.
     * Below [MIN_GAPS_FOR_EMPIRICAL] samples the estimate comes from type size
     * instead, and even with samples it is capped — a "median" far larger than the
     * type size is not leading, it is a page of well-separated fragments.
     */
    private fun estimateLeading(lines: List<Line>, bodySize: Float): Float {
        val gaps = lines.zipWithNext { a, b -> a.y - b.y }.filter { it > 0.5f }.sorted()
        val empirical = if (gaps.size >= MIN_GAPS_FOR_EMPIRICAL) gaps[gaps.size / 2] else 0f
        val typographic = if (bodySize > 0f) bodySize * TYPICAL_LEADING_RATIO else 0f
        return when {
            empirical > 0f && typographic > 0f ->
                minOf(empirical, typographic * MAX_LEADING_RATIO)
            empirical > 0f -> empirical
            else -> typographic
        }
    }
}
