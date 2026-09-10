package app.folio.core.paginate

import app.folio.core.model.Chapter
import app.folio.core.model.ContentBlock
import app.folio.core.model.ReadingPosition
import app.folio.core.model.plainText

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
        /** Space after a paragraph, as a multiple of its line height. */
        const val PARAGRAPH_SPACING = 0.55f
        /** Extra space above a heading. */
        const val HEADING_SPACING_ABOVE = 1.4f
        /** Headings are set larger than body text. */
        val HEADING_SCALE = mapOf(1 to 1.6f, 2 to 1.4f, 3 to 1.2f)
        /**
         * A heading with fewer than this many body lines after it is pushed to the
         * next page. A chapter title stranded alone at the foot of a page is the
         * one typographic sin worth code to avoid.
         */
        const val MIN_LINES_AFTER_HEADING = 2
    }

    fun paginate(
        chapter: Chapter,
        viewport: Viewport,
        settings: TypographySettings,
    ): List<Page> {
        if (viewport.widthPx <= 0f || viewport.heightPx <= 0f) return listOf(Page(emptyList()))

        val blocks = chapter.blocks
        if (blocks.isEmpty()) return listOf(Page(emptyList()))

        val pages = mutableListOf<Page>()
        var current = mutableListOf<PageSlice>()
        var used = 0f

        fun flush() {
            pages += Page(current)
            current = mutableListOf()
            used = 0f
        }

        blocks.forEachIndexed { blockIndex, block ->
            val text = block.plainText
            val style = styleFor(block, settings)
            val spacingAbove = spacingAbove(block, settings, isFirstOnPage = current.isEmpty())

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
                val slice = text.substring(cursor)

                if (remainingHeight < style.lineHeightPx) {
                    // Not even one line fits. Start a new page unless this page is
                    // already empty, in which case the viewport is smaller than a
                    // single line and looping would never terminate.
                    if (current.isEmpty() && used == 0f) {
                        current += PageSlice(blockIndex, cursor, text.length)
                        cursor = text.length
                        break
                    }
                    flush()
                    spacing = 0f
                    continue
                }

                val measured = measurer.measure(slice, style, viewport.widthPx)

                if (measured.heightPx <= remainingHeight) {
                    current += PageSlice(blockIndex, cursor, text.length)
                    used += spacing + measured.heightPx
                    cursor = text.length
                } else {
                    val linesThatFit = (remainingHeight / style.lineHeightPx).toInt()
                        .coerceAtMost(measured.lineCount)

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
                used += trailingSpacing(block, settings)
            }
        }

        if (current.isNotEmpty() || pages.isEmpty()) flush()

        return applyHeadingOrphanControl(pages, blocks)
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

    private fun styleFor(block: ContentBlock, settings: TypographySettings): BlockStyle =
        when (block) {
            is ContentBlock.Heading -> {
                val scale = HEADING_SCALE[block.level] ?: 1.1f
                BlockStyle(
                    fontSizeSp = settings.fontSizeSp * scale,
                    lineHeightPx = settings.bodyLineHeightPx * scale,
                    bold = true,
                )
            }

            is ContentBlock.BlockQuote -> BlockStyle(
                fontSizeSp = settings.fontSizeSp,
                lineHeightPx = settings.bodyLineHeightPx,
                italic = true,
            )

            else -> BlockStyle(
                fontSizeSp = settings.fontSizeSp,
                lineHeightPx = settings.bodyLineHeightPx,
            )
        }

    private fun spacingAbove(
        block: ContentBlock,
        settings: TypographySettings,
        isFirstOnPage: Boolean,
    ): Float {
        if (isFirstOnPage) return 0f
        return when (block) {
            is ContentBlock.Heading -> settings.bodyLineHeightPx * HEADING_SPACING_ABOVE
            else -> settings.bodyLineHeightPx * PARAGRAPH_SPACING
        }
    }

    private fun trailingSpacing(block: ContentBlock, settings: TypographySettings): Float =
        when (block) {
            is ContentBlock.Heading -> settings.bodyLineHeightPx * 0.3f
            else -> 0f
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
