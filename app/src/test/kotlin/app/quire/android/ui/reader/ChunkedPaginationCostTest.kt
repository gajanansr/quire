package app.quire.android.ui.reader

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.model.ReadingPosition
import app.quire.core.paginate.BlockStyle
import app.quire.core.paginate.Measure
import app.quire.core.paginate.Measured
import app.quire.core.paginate.PageWindow
import app.quire.core.paginate.Paginator
import app.quire.core.paginate.TextMeasurer
import app.quire.core.paginate.TypographySettings
import app.quire.core.paginate.Viewport
import app.quire.core.reading.TextAnchor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a type-size change costs on a 566,000-character chapter.
 *
 * In the spirit of `PaginationCostTest`: **characters measured, not milliseconds**,
 * so the number is the same on any machine and the improvement is a fact in the
 * repository rather than a claim about a phone nobody else has.
 *
 * The book is *The Love Hypothesis*, a 315-page PDF whose outline is unusable, so
 * `ChapterDetector` correctly falls back to `single` and the whole novel imports as
 * **one chapter of 8,621 blocks and 565,896 characters**. Pagination laid all of it
 * out on open and again on every tap of A+.
 *
 * The fixture is that shape, not that book: the same block count and the same total,
 * so the block-level and character-level costs are both to scale.
 */
class ChunkedPaginationCostTest {

    /** Counts what the paginator asks to have laid out. */
    private class CountingMeasurer : TextMeasurer {
        var calls = 0
        var charsMeasured = 0L

        override fun measure(text: String, style: BlockStyle, widthPx: Float): Measured {
            calls++
            charsMeasured += text.length
            val perLine = (widthPx / (style.fontSizeSp * AVERAGE_CHAR_EM * PIXELS_PER_SP))
                .toInt().coerceAtLeast(1)
            val ends = mutableListOf<Int>()
            var cursor = 0
            while (cursor < text.length) {
                cursor = (cursor + perLine).coerceAtMost(text.length)
                ends += cursor
            }
            if (ends.isEmpty()) ends += 0
            return Measured(ends.size * style.lineHeightPx, ends)
        }

        private companion object {
            const val AVERAGE_CHAR_EM = 0.5f
        }
    }

    private companion object {
        /** A common 1080x2400 phone. */
        const val PIXELS_PER_SP = 2.75f

        /** The device's own report: 8,621 blocks, 565,896 characters, one chapter. */
        const val REAL_BLOCKS = 8_621
        const val REAL_CHARS = 565_896

        /** What the Reader lays out in one go — behind the reader, and ahead of them. */
        val WINDOW_PAGES = ReaderWindow.PAGES_BEHIND + ReaderWindow.PAGES_AHEAD
    }

    private fun settings(sizeSp: Float) = TypographySettings(
        fontSizeSp = sizeSp, lineHeightMultiple = 1.55f, pixelsPerSp = PIXELS_PER_SP,
    )

    // ReaderScreen's own paddings: 26dp at the sides, 26dp above, 40dp below, plus
    // the system bars, on a 393x873dp screen.
    private val availableWidthPx = (393f - 52f) * PIXELS_PER_SP
    private val viewportHeightPx = (873f - 24f - 24f - 26f - 40f) * PIXELS_PER_SP

    private fun viewport(sizeSp: Float) =
        Viewport(Measure.widthPx(availableWidthPx, settings(sizeSp)), viewportHeightPx)

    private fun prose(chars: Int) = buildString {
        while (length < chars) {
            append(
                "The coffee was terrible, which was the only thing about the morning " +
                    "that felt reliable. She read the sentence again and it still said " +
                    "what it had said the first time. ",
            )
        }
    }.take(chars)

    private fun bookOf(blocks: Int, chars: Int): Chapter {
        val each = prose(chars / blocks)
        val list = List<ContentBlock>(blocks) { ContentBlock.Paragraph(listOf(InlineSpan(each))) }
        val chapter = Chapter(0, null, list, 0, 0)
        return chapter.copy(charCount = chapter.textLength)
    }

    private val book = bookOf(REAL_BLOCKS, REAL_CHARS)

    /** Characters the paginator asked to have laid out, doing the whole chapter. */
    private fun wholeChapterCost(chapter: Chapter, sizeSp: Float): Long {
        val m = CountingMeasurer()
        Paginator(m).paginate(chapter, viewport(sizeSp), settings(sizeSp))
        return m.charsMeasured
    }

    /** The same, opening a window where the reader left off. */
    private fun windowCost(chapter: Chapter, sizeSp: Float, at: Int): Long {
        val m = CountingMeasurer()
        val paginator = Paginator(m)
        val view = viewport(sizeSp)
        val set = settings(sizeSp)
        val cursor = chapter.cursorAt(at)
        runBlocking {
            ReaderWindow.openAt(
                chapter,
                ReadingPosition(0, cursor.blockIndex, cursor.charOffset),
                ReaderWindow.charsBehind(view, set),
            ) { from, maxPages -> paginator.paginateWindow(chapter, from, maxPages, view, set) }
        }
        return m.charsMeasured
    }

    // ------------------------------------------------------------ the headline

    @Test
    fun `a type-size change lays out a fraction of what it used to`() {
        // Measured, at the three ends of the stepper, half way through the book:
        //
        // | type | whole chapter | a window | ratio |
        // |---|---|---|---|
        // | 15sp | 560,365 | 20,805 | 27x |
        // | 19sp | 607,035 | 16,900 | 36x |
        // | 24sp | 609,427 |  9,166 | 66x |
        //
        // A window is cheaper at large type because a page holds less of the book,
        // and a window is a fixed number of pages. The whole chapter costs the same
        // whatever the reader chose, which is the shape of the problem.
        //
        // The floor is asserted at the worst of the three, because the reader picks
        // the type size and the guarantee has to hold whichever they pick.
        listOf(15f, 19f, 24f).forEach { size ->
            val whole = wholeChapterCost(book, size)
            val window = windowCost(book, size, at = REAL_CHARS / 2)
            val ratio = whole.toDouble() / window
            println(
                "cost ${size}sp: whole=$whole window=$window ratio=${"%.0f".format(ratio)}x",
            )
            assertTrue(
                "at ${size}sp a window laid out $window characters of the chapter's " +
                    "$whole — only ${"%.1f".format(ratio)}x cheaper",
                ratio > 20.0,
            )
        }
    }

    @Test
    fun `what a window costs does not depend on how long the book is`() {
        // The property, rather than the constant. Every previous performance bug here
        // — the substring scan of 2026-09-12, the block scan of 2026-09-15 — had the
        // same signature: the cost per character rose with the size of the book. A
        // window must be flat, because its size is set by the viewport and the type
        // size and by nothing about the chapter it is cut from.
        val small = windowCost(bookOf(2_000, 130_000), 19f, at = 65_000)
        val large = windowCost(bookOf(16_000, 1_040_000), 19f, at = 520_000)
        assertTrue(
            "a window cost $small characters in a 130k chapter and $large in a 1,040k " +
                "one, an eightfold book",
            large < small * 1.5,
        )
    }

    @Test
    fun `a window costs about the pages it holds`() {
        // Not merely "less than the chapter": the work has to be proportional to what
        // the reader can actually see. Twenty pages of a chapter whose pages hold
        // about 780 characters at 19sp is about 15,600 characters, and the paginator's
        // own overhead on top of that is the 1.35x `PaginationCostTest` pins.
        val perPage = Measure.charsPerPage(viewport(19f), settings(19f))
        val window = windowCost(book, 19f, at = REAL_CHARS / 2)
        assertTrue(
            "a $WINDOW_PAGES-page window laid out $window characters, against about " +
                "${WINDOW_PAGES * perPage} of text",
            window < WINDOW_PAGES * perPage * 3,
        )
    }

    // ------------------------------------------------------- reading onwards

    @Test
    fun `reading on costs one chunk, not one window`() {
        // What the reader pays every twelve pages, and pays in the background before
        // they arrive. It must be smaller than opening the book, or a prefetch would
        // be as expensive as the thing it exists to avoid.
        val m = CountingMeasurer()
        val paginator = Paginator(m)
        val view = viewport(19f)
        val set = settings(19f)
        val lay: suspend (TextAnchor, Int) -> PageWindow =
            { from, maxPages -> paginator.paginateWindow(book, from, maxPages, view, set) }

        runBlocking {
            val cursor = book.cursorAt(REAL_CHARS / 2)
            val opened = ReaderWindow.openAt(
                book, ReadingPosition(0, cursor.blockIndex, cursor.charOffset),
                ReaderWindow.charsBehind(view, set), lay,
            )
            val opening = m.charsMeasured
            ReaderWindow.extendedForward(opened.copy(pageIndex = opened.pages.lastIndex), lay)
            val extension = m.charsMeasured - opening
            println("extension=$extension of an opening of $opening")
            assertTrue(
                "an extension laid out $extension characters against an opening of $opening",
                extension < opening,
            )
            assertTrue("nothing was laid out by the extension", extension > 0)
        }
    }

    @Test
    fun `a book already on the device needs nothing done to it`() {
        // Stated as a test because it is the constraint most easily lost in a
        // refactor. A chunk is a cursor into a `Chapter` that is already on disk,
        // worked out in memory at lay-out time. Nothing about chunking is serialized,
        // so nothing needs re-importing, re-extracting or migrating — and if a chunk
        // boundary ever became a stored field, this would fail.
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val encoded = json.encodeToString(Chapter.serializer(), bookOf(4, 400))
        listOf("windowStart", "chunk", "blockStarts", "next", "pageIndex").forEach { field ->
            assertTrue(
                "a chunk boundary reached the stored chapter as \"$field\"",
                !encoded.contains("\"$field\""),
            )
        }
    }
}
