package app.quire.core.paginate

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.ReadingPosition
import app.quire.core.reading.TextAnchor
import kotlin.coroutines.cancellation.CancellationException

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
     * @param isActive asked once per finished page, and pagination abandons the
     *   chapter the moment it answers false. This is an ordinary function called
     *   inside `withContext(Dispatchers.Default)`, so cancelling the coroutine around
     *   it does not stop it: a reader stepping the type size four times had four
     *   paginations of a long chapter competing for the same cores, three of which
     *   would be thrown away. Abandoning throws rather than returning the pages so
     *   far, because a short page list is indistinguishable from a short chapter and
     *   would be cached, and resolving a reading position against it would move the
     *   reader somewhere they have never been.
     */
    fun paginate(
        chapter: Chapter,
        viewport: Viewport,
        settings: TypographySettings,
        firstPageInsetPx: Float = 0f,
        isActive: () -> Boolean = { true },
    ): List<Page> = paginateWindow(
        chapter = chapter,
        from = TextAnchor(0, 0),
        maxPages = Int.MAX_VALUE,
        viewport = viewport,
        settings = settings,
        firstPageInsetPx = firstPageInsetPx,
        isActive = isActive,
    ).pages

    /**
     * Lays out at most [maxPages] pages of [chapter], starting at [from].
     *
     * **This is the whole of chunking.** A chapter with no outline is one chapter —
     * 8,621 blocks and 565,896 characters for a 315-page novel — and laying all of it
     * out on open, and again on every tap of the type stepper, is what made such a
     * book hang. A chunk is a window over the same chapter: the reader's place plus
     * enough either side of it, laid out in real time as they read.
     *
     * The returned [PageWindow.next] is where the following chunk begins, and it is
     * always a boundary between two pages this run produced. That is what makes a
     * seam invisible rather than merely small:
     *
     * - **Every page but the chapter's last is full.** The only page a chunk can end
     *   on is one `flush()` produced inside the layout loop, which is a page that ran
     *   out of height (or was deliberately shortened by orphan control, which is the
     *   same decision whole-chapter pagination would make there).
     * - **A chunk resumed at a page boundary is in the same state whole-chapter
     *   pagination is in at that boundary** — `used` is zero, the page is empty, the
     *   spacing above the first block on a page is zero by definition, and both the
     *   paragraph indent and the raised initial are suppressed for a block resumed
     *   part-way through. So the pages are identical, not merely similar, and
     *   `ChunkedPaginationTest` asserts that slice for slice.
     * - **A cut inside a block needs no special case**, which is why chunks are not
     *   cut at paragraph boundaries: the one-block chapter — a TXT with no blank
     *   lines, a PDF whose reflow merged everything — has no paragraph boundary to
     *   cut at, and is exactly the shape that causes the problem.
     *
     * [firstPageInsetPx] belongs to the chapter's first page and therefore to the
     * first chunk only. Charging it again at a seam would lay that page out for less
     * than it draws, and clip the last line off it.
     */
    fun paginateWindow(
        chapter: Chapter,
        from: TextAnchor,
        maxPages: Int,
        viewport: Viewport,
        settings: TypographySettings,
        firstPageInsetPx: Float = 0f,
        isActive: () -> Boolean = { true },
    ): PageWindow {
        val blocks = chapter.blocks
        val firstBlock = from.blockIndex.coerceAtLeast(0)
        if (viewport.widthPx <= 0f || viewport.heightPx <= 0f ||
            blocks.isEmpty() || firstBlock >= blocks.size
        ) {
            // One empty page rather than none. An empty page list reports "this is
            // the last page", which the Reader once read as a chapter boundary and
            // abandoned the turn — see ReaderState.pendingTurns.
            return PageWindow(from, listOf(Page(emptyList())), null)
        }

        val pages = mutableListOf<Page>()
        var current = mutableListOf<PageSlice>()
        var used = firstPageInsetPx.coerceAtLeast(0f)
        // Learned from each measurement and carried forward, so the window starts
        // close to right rather than doubling its way there on every page.
        //
        // Reset at a chunk boundary, which is the one piece of state a chunk does not
        // inherit. It decides how much text a single `measure` call asks for, never
        // where a line breaks, so it costs a measurement or two at a seam and cannot
        // move a page break.
        var charsPerLine = ASSUMED_CHARS_PER_LINE
        var stopped = false

        val blockTexts = chapter.blockTexts

        fun flush() {
            // Once per page: often enough that a superseded run stops within a frame
            // or two, rarely enough to cost nothing when nothing is superseding it.
            if (!isActive()) throw CancellationException("pagination superseded")
            val page = Page(current)
            pages += page
            current = mutableListOf()
            used = 0f
            if (pages.size >= maxPages && mayEndAChunk(page, blocks)) stopped = true
        }

        // Found once for the chapter, not searched for once per block. Asking per
        // block is what made pagination quadratic in block count.
        val openingIndex = ChapterOpening.openingIndex(blocks)
        var blockIndex = firstBlock
        while (blockIndex < blocks.size && !stopped) {
            val block = blocks[blockIndex]
            val text = blockTexts[blockIndex]
            // Asked with startChar = 0 whatever this chunk starts at, exactly as the
            // whole-chapter run asks it, because both answers are suppressed below
            // for a block resumed part-way through.
            val opensChapter = ChapterOpening.opensChapter(openingIndex, blockIndex, startChar = 0)
            val baseStyle = BlockStyles.of(block, settings)
                .copy(openingInitial = opensChapter)
            val indented = Indentation.shouldIndent(blocks, blockIndex, startChar = 0)
            val spacingAbove = spacingAbovePx(block, settings, isFirstOnPage = current.isEmpty())

            if (text.isEmpty()) {
                // A page break or an image with no caption still occupies its slot,
                // so it is recorded with zero length rather than skipped: dropping
                // it would shift every later block index.
                current += PageSlice(blockIndex, 0, 0)
                used += spacingAbove
                blockIndex++
                continue
            }

            // Only the block this chunk opens on starts anywhere but its beginning.
            var cursor =
                if (blockIndex == firstBlock) from.charOffset.coerceIn(0, text.length) else 0
            var spacing = spacingAbove

            while (cursor < text.length && !stopped) {
                val remainingHeight = viewport.heightPx - used - spacing

                if (remainingHeight < baseStyle.lineHeightPx) {
                    // Not even one line fits. Start a new page unless this page is
                    // already empty, in which case the viewport is smaller than a
                    // single line of this block and looping would never terminate.
                    //
                    // The emptiness of the page is the whole of the test. It used to
                    // also require `pages.isEmpty()`, which saved only the *chapter's*
                    // first page: a viewport shorter than one line of a mid-chapter
                    // block — a level-one heading is 1.6x the body — flushed empty
                    // pages for ever, and `maxPages` cannot bound that because
                    // `mayEndAChunk` correctly refuses to end a chunk on an empty
                    // page. It also made a chunk's first page behave differently from
                    // the same page in a whole-chapter run, which was the one hole in
                    // the identity `ChunkedPaginationTest` asserts.
                    if (current.isEmpty() && used <= firstPageInsetPx) {
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
                val style = when {
                    cursor > 0 -> baseStyle.copy(openingInitial = false)
                    indented -> baseStyle.copy(
                        firstLineIndentPx = BlockStyles.firstLineIndentPx(settings),
                    )
                    else -> baseStyle
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
            if (cursor >= text.length) blockIndex++
        }

        // Captured before the final flush, which can set `stopped` on a chunk that
        // happened to fill its budget with the chapter's last page.
        val reachedChapterEnd = !stopped
        if (reachedChapterEnd && (current.isNotEmpty() || pages.isEmpty())) flush()

        val out = applyHeadingOrphanControl(pages, blocks)
        return PageWindow(
            start = from,
            pages = out,
            next = if (reachedChapterEnd) null else carryAfter(out.last(), blocks, blockTexts),
        )
    }

    /**
     * Whether a chunk may end on this page.
     *
     * Two pages it may not, and both would show as a seam:
     *
     * - **A page with nothing on it.** It carries no cursor to resume from, and a
     *   blank page between two full ones is as visible as a seam gets.
     * - **A page whose last block is a heading that orphan control is about to
     *   move.** That pass runs over the pages one lay-out produced, so a chunk that
     *   cannot see the next page cannot move the heading off this one — and a chapter
     *   title stranded at the foot of a page would then happen at a seam and nowhere
     *   else. Running on by one page puts the heading exactly where whole-chapter
     *   pagination puts it. A page that is *only* headings is a chapter opening and
     *   belongs where it is, which is the same exception `applyHeadingOrphanControl`
     *   makes.
     */
    private fun mayEndAChunk(page: Page, blocks: List<ContentBlock>): Boolean {
        if (page.slices.isEmpty()) return false
        val trailing = page.slices.takeLastWhile {
            blocks.getOrNull(it.blockIndex) is ContentBlock.Heading
        }
        return trailing.isEmpty() || trailing.size == page.slices.size
    }

    /**
     * Where the chunk after [page] begins.
     *
     * Read off the page's end rather than tracked alongside it, because
     * `applyHeadingOrphanControl` can add slices to the *front* of the last page and
     * a separately tracked cursor would then describe a page that no longer exists.
     *
     * A slice that consumed its block to the end hands over at the next block rather
     * than at (block, length): the two mean the same place, and the layout loop only
     * produces the first, so keeping one spelling is what lets the identity with
     * whole-chapter pagination be an equality.
     */
    private fun carryAfter(
        page: Page,
        blocks: List<ContentBlock>,
        blockTexts: List<String>,
    ): TextAnchor? {
        val last = page.slices.lastOrNull() ?: return null
        val text = blockTexts.getOrNull(last.blockIndex).orEmpty()
        val at = if (last.endChar >= text.length) TextAnchor(last.blockIndex + 1, 0)
        else TextAnchor(last.blockIndex, last.endChar)
        // Past the last block is not a place: the chapter ended on this page.
        return at.takeIf { it.blockIndex < blocks.size }
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

            // Enough when there is no more text, or when the page is provably full
            // *and* the widow rule cannot need to know what comes after it.
            //
            // Stopping as soon as `lineCount > maxLines` was not enough, and it is the
            // one place where the learned estimate could move a page break. The widow
            // pull-back fires only when the block has exactly [MIN_FRAGMENT_LINES] - 1
            // lines left over, and it can only know that by having reached the end —
            // so a window that stops one line past the page answers "I did not reach
            // the end" when a slightly wider one would have answered "I did, and there
            // is one line left". A chunk starts with the estimate reset, so the two
            // runs land on different sides of that and break the page differently.
            // Measured on a fixture of blocks exactly one line longer than a page:
            // whole-chapter cut at 24 lines, a fresh chunk at 25.
            //
            // A window showing [MIN_FRAGMENT_LINES] or more lines past the page needs
            // no widening: whatever follows, the remainder is already too big to pull
            // back.
            if (reachedEnd || measured.lineCount >= maxLines + MIN_FRAGMENT_LINES) {
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
