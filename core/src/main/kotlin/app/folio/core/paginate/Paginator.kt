package app.folio.core.paginate

import app.folio.core.model.Chapter
import app.folio.core.model.ContentBlock
import app.folio.core.model.ReadingPosition

/** A run of one block's characters placed on a page. */
data class PageSlice(
    val blockIndex: Int,
    val startChar: Int,
    val endChar: Int,
) {
    val length: Int get() = endChar - startChar
}

data class Page(val slices: List<PageSlice>) {
    val isEmpty: Boolean get() = slices.all { it.length == 0 }
}

/**
 * Breaks a chapter into pages that fit the viewport.
 *
 * Works one chapter at a time, never the whole book: paginating four hundred pages
 * to show one would stall the Reader, and the result would be thrown away the moment
 * the reader changed the type size.
 *
 * Splits only at line boundaries the measurer reports, so a page break never lands
 * inside a rendered line. The invariant that matters most is conservation: every
 * character of the chapter appears exactly once across the returned pages, in order.
 * A paginator that quietly drops a sentence is far worse than one that breaks a page
 * awkwardly, and only the first is silent.
 */
class Paginator(private val measurer: TextMeasurer) {

    private companion object {
        /**
         * A heading with fewer than this many body lines after it is pushed to the
         * next page. A chapter title stranded alone at the foot of a page is the
         * one typographic sin worth code to avoid.
         */
        const val MIN_LINES_AFTER_HEADING = 2

        /**
         * Fewest lines of a paragraph worth leaving on a page by themselves.
         *
         * Two. One line alone at the foot of a page is an orphan and one alone at
         * the head of the next is a widow; both read as mistakes, and both are
         * fixed by moving a line rather than by breaking there.
         */
        const val MIN_FRAGMENT_LINES = 2

        /** Characters per line assumed before any measurement has been seen. */
        const val ASSUMED_CHARS_PER_LINE = 80f

        /**
         * How much more than a page to ask for, so one measurement usually settles
         * the page. Too small and the window doubles repeatedly; too large and the
         * saving over measuring the whole remainder shrinks.
         */
        const val WINDOW_SLACK = 1.3f
    }

    /**
     * @param firstPageInsetPx height consumed on the first page by the chapter
     *   header. Pagination has to budget for anything sharing the text's box: given
     *   the full height it packs more lines than will fit and the last one is
     *   clipped, which looks like text simply going missing.
     */
    fun paginate(
        chapter: Chapter,
        viewport: Viewport,
        settings: TypographySettings,
        firstPageInsetPx: Float = 0f,
    ): List<Page> {
        if (viewport.widthPx <= 0f || viewport.heightPx <= 0f) return listOf(Page(emptyList()))

        val blocks = chapter.blocks
        if (blocks.isEmpty()) return listOf(Page(emptyList()))

        val pages = mutableListOf<Page>()
        var current = mutableListOf<PageSlice>()
        var used = firstPageInsetPx.coerceAtLeast(0f)
        // Learned from each measurement and carried forward, so the window starts
        // close to right rather than doubling its way there on every page.
        var charsPerLine = ASSUMED_CHARS_PER_LINE

        fun flush() {
            pages += Page(current)
            current = mutableListOf()
            used = 0f
        }

        val blockTexts = chapter.blockTexts
        blocks.forEachIndexed { blockIndex, block ->
            val text = blockTexts[blockIndex]
            val baseStyle = BlockStyles.of(block, settings)
            val indented = Indentation.shouldIndent(blocks, blockIndex, startChar = 0)
            val spacingAbove = spacingAbovePx(block, settings, isFirstOnPage = current.isEmpty())

            if (text.isEmpty()) {
                // A page break or an image with no caption still occupies its slot,
                // so it is recorded with zero length rather than skipped: dropping
                // it would shift every later block index.
                current += PageSlice(blockIndex, 0, 0)
                used += spacingAbove
                return@forEachIndexed
            }

            var cursor = 0
            var spacing = spacingAbove

            while (cursor < text.length) {
                val remainingHeight = viewport.heightPx - used - spacing

                if (remainingHeight < baseStyle.lineHeightPx) {
                    // Not even one line fits. Start a new page unless this page is
                    // already empty, in which case the viewport is smaller than a
                    // single line and looping would never terminate.
                    if (current.isEmpty() && pages.isEmpty() && used <= firstPageInsetPx) {
                        current += PageSlice(blockIndex, cursor, text.length)
                        cursor = text.length
                        break
                    }
                    flush()
                    spacing = 0f
                    continue
                }

                val maxLines = (remainingHeight / baseStyle.lineHeightPx).toInt()
                // The indent belongs to a block's opening line only; once the text
                // has been cut across a page the remainder starts at the margin.
                val style = if (indented && cursor == 0) {
                    baseStyle.copy(firstLineIndentPx = BlockStyles.firstLineIndentPx(settings))
                } else {
                    baseStyle
                }
                val window = measureWindow(
                    text, cursor, maxLines, charsPerLine, style,
                    viewport.widthPx - style.indentPx,
                )
                val measured = window.measured
                charsPerLine = window.charsPerLine

                if (window.reachedEnd && measured.heightPx <= remainingHeight) {
                    current += PageSlice(blockIndex, cursor, text.length)
                    used += spacing + measured.heightPx
                    cursor = text.length
                } else {
                    var linesThatFit = maxLines.coerceAtMost(measured.lineCount)

                    // No stranded lines. One line of a paragraph left at the foot of
                    // a page, or carried alone to the top of the next, is the thing
                    // typesetters remove last and readers notice first.
                    if (linesThatFit in 1 until MIN_FRAGMENT_LINES && current.isNotEmpty()) {
                        // An orphan: too little of the paragraph fits here, so the
                        // whole fragment goes over rather than a line of it.
                        flush()
                        spacing = 0f
                        continue
                    }
                    if (window.reachedEnd) {
                        // The remainder is known exactly, so a widow can be seen
                        // coming: pull a line back so two travel together.
                        val remaining = measured.lineCount - linesThatFit
                        if (remaining in 1 until MIN_FRAGMENT_LINES &&
                            linesThatFit > MIN_FRAGMENT_LINES
                        ) {
                            linesThatFit -= MIN_FRAGMENT_LINES - remaining
                        }
                    }

                    if (linesThatFit <= 0) {
                        flush()
                        spacing = 0f
                        continue
                    }

                    val cut = measured.lineEnds[linesThatFit - 1]
                    if (cut <= 0) {
                        flush()
                        spacing = 0f
                        continue
                    }

                    current += PageSlice(blockIndex, cursor, cursor + cut)
                    cursor += cut
                    flush()
                    spacing = 0f
                }
            }

            if (cursor >= text.length && current.isNotEmpty()) {
                used += trailingSpacingPx(block, settings)
            }
        }

        if (current.isNotEmpty() || pages.isEmpty()) flush()

        return applyHeadingOrphanControl(pages, blocks)
    }

    /** A measurement of part of a block, and what it taught us about line length. */
    private data class Window(
        val measured: Measured,
        val reachedEnd: Boolean,
        val charsPerLine: Float,
    )

    /**
     * Measures just enough of a block to decide where this page ends.
     *
     * The paginator used to hand the measurer `text.substring(cursor)` — the entire
     * rest of the block — to find one page of lines. For a block split across P
     * pages that measures the text P/2 times over: a 400k-character block laid out
     * 30.1M characters, and the ratio doubled every time the book doubled. Ordinary
     * paragraphs hid it, because each one fits in a page or two; one enormous block
     * is what made long books hang, and what made every type-size change hang again.
     *
     * Only two outcomes matter to the caller: either the rest of the block fits, or
     * there are more lines than the page can hold. So it is enough to measure a
     * window predicted to overflow [maxLines], and widen it only when the prediction
     * was short. The estimate is learned from real measurements as it goes, so after
     * the first page the window is usually right first time.
     */
    private fun measureWindow(
        text: String,
        cursor: Int,
        maxLines: Int,
        charsPerLine: Float,
        style: BlockStyle,
        widthPx: Float,
    ): Window {
        var estimate = charsPerLine
        var windowChars = (((maxLines + 2) * estimate) * WINDOW_SLACK).toInt().coerceAtLeast(1)

        while (true) {
            val windowEnd = (cursor + windowChars).coerceAtMost(text.length)
            val measured = measurer.measure(
                text.substring(cursor, windowEnd), style, widthPx,
            )
            val reachedEnd = windowEnd >= text.length

            // Learn from complete lines only. The last line of a window is cut by
            // the window, not by the margin, so including it would drag the estimate
            // down and cause the next window to be too small.
            if (measured.lineCount >= 2) {
                val complete = measured.lineEnds[measured.lineCount - 2]
                estimate = (complete.toFloat() / (measured.lineCount - 1)).coerceAtLeast(1f)
            }

            // Enough when the page is provably full, or there is no more text.
            if (reachedEnd || measured.lineCount > maxLines) {
                return Window(measured, reachedEnd, estimate)
            }
            windowChars *= 2
        }
    }

    /**
     * Moves a heading that ended a page onto the next one.
     *
     * Cheaper and clearer as a pass over finished pages than as another condition
     * inside the layout loop.
     */
    private fun applyHeadingOrphanControl(
        pages: List<Page>,
        blocks: List<ContentBlock>,
    ): List<Page> {
        if (pages.size < 2) return pages

        val out = pages.map { it.slices.toMutableList() }.toMutableList()
        for (i in 0 until out.size - 1) {
            val page = out[i]
            val trailingHeadings = page.takeLastWhile { slice ->
                blocks.getOrNull(slice.blockIndex) is ContentBlock.Heading
            }
            if (trailingHeadings.isEmpty()) continue
            // Only move it if something remains on this page; a page that is only a
            // heading is a chapter opening and belongs exactly where it is.
            if (trailingHeadings.size == page.size) continue

            repeat(trailingHeadings.size) { page.removeAt(page.lastIndex) }
            out[i + 1].addAll(0, trailingHeadings)
        }
        return out.map { Page(it) }
    }

}

/**
 * The page holding a position, or 0 when it cannot be placed.
 *
 * Falling back to the first page rather than throwing matters: a position saved
 * before the book was reprocessed may point at a block that no longer exists, and
 * losing your place is better than a crash on open.
 */
fun List<Page>.pageContaining(position: ReadingPosition): Int {
    val index = indexOfFirst { page ->
        page.slices.any { slice ->
            slice.blockIndex == position.blockIndex &&
                position.charOffset >= slice.startChar &&
                (position.charOffset < slice.endChar || slice.length == 0)
        }
    }
    if (index >= 0) return index

    // The offset may sit past the end of its block; fall back to the page that
    // holds the block at all.
    val byBlock = indexOfFirst { page -> page.slices.any { it.blockIndex == position.blockIndex } }
    return if (byBlock >= 0) byBlock else 0
}

/** The position at the start of a page, for saving as the reader turns. */
fun Page.startPosition(chapterIndex: Int): ReadingPosition {
    val first = slices.firstOrNull() ?: return ReadingPosition(chapterIndex, 0, 0)
    return ReadingPosition(chapterIndex, first.blockIndex, first.startChar)
}

/** Total characters placed across pages, for conservation checks. */
fun List<Page>.characterCount(): Int = sumOf { page -> page.slices.sumOf { it.length } }
