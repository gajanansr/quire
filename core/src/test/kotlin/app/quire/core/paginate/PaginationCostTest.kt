package app.quire.core.paginate

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Counts what the paginator actually asks the measurer to lay out. */
private class CountingMeasurer(private val charsPerLine: Int = 40) : TextMeasurer {
    var calls = 0
    var charsMeasured = 0L

    override fun measure(text: String, style: BlockStyle, widthPx: Float): Measured {
        calls++
        charsMeasured += text.length
        val perLine = (charsPerLine * (19f / style.fontSizeSp)).toInt().coerceAtLeast(1)
        val lineEnds = mutableListOf<Int>()
        var cursor = 0
        while (cursor < text.length) {
            cursor = (cursor + perLine).coerceAtMost(text.length)
            lineEnds += cursor
        }
        if (lineEnds.isEmpty()) lineEnds += 0
        return Measured(heightPx = lineEnds.size * style.lineHeightPx, lineEnds = lineEnds)
    }
}

/**
 * What pagination costs, as a property rather than a stopwatch.
 *
 * The bug this guards was quadratic: the paginator handed the measurer the whole
 * remaining block to find one page of lines, so a 400k-character block laid out
 * 30.1M characters — 75x its own length, doubling every time the book doubled.
 * Ordinary paragraph blocks hid it at 1.2x; a single huge block (a TXT with no
 * blank lines, a PDF whose reflow merged everything) is what made long books hang.
 *
 * Counting characters rather than timing makes this deterministic on any machine,
 * and states the real invariant: work must stay proportional to the text.
 *
 * Measured after the fix: 1.35x at every size, one measurement per page. The
 * thresholds here sit above that with room for ordinary drift, and far below the
 * behaviour they exist to catch.
 */
class PaginationCostTest {

    private val settings = TypographySettings(fontSizeSp = 19f, lineHeightMultiple = 1.55f)
    private val viewport = Viewport(widthPx = 1000f, heightPx = 2000f)

    private fun prose(chars: Int) = buildString {
        while (length < chars) append("Distributed systems are a collection of independent computers. ")
    }.take(chars)

    private fun oneBlock(chars: Int): Chapter {
        val text = prose(chars)
        return Chapter(
            index = 0, title = "Long",
            blocks = listOf(ContentBlock.Paragraph(listOf(InlineSpan(text)))),
            startCharOffset = 0, charCount = text.length,
        )
    }

    private fun paragraphs(chars: Int, each: Int = 1200): Chapter {
        val one = prose(each)
        val blocks = (0 until (chars / each)).map {
            ContentBlock.Paragraph(listOf(InlineSpan(one)))
        }
        return Chapter(
            index = 0, title = "Long", blocks = blocks,
            startCharOffset = 0, charCount = blocks.size * each,
        )
    }

    private fun ratioFor(chapter: Chapter, chars: Int): Double {
        val m = CountingMeasurer()
        Paginator(m).paginate(chapter, viewport, settings)
        return m.charsMeasured.toDouble() / chars
    }

    @Test
    fun `one huge block costs about what its own length costs`() {
        val ratio = ratioFor(oneBlock(400_000), 400_000)
        assertTrue(
            ratio < 2.0,
            "laid out ${"%.1f".format(ratio)}x the chapter's length",
        )
    }

    @Test
    fun `cost does not grow as the block grows`() {
        // The signature of the quadratic: the ratio doubled with the size. Whatever
        // the constant is, it must not depend on how long the book is.
        val small = ratioFor(oneBlock(100_000), 100_000)
        val large = ratioFor(oneBlock(400_000), 400_000)
        assertTrue(
            large < small * 1.5,
            "cost per character grew from ${"%.1f".format(small)}x to ${"%.1f".format(large)}x",
        )
    }

    @Test
    fun `ordinary paragraphs stay cheap`() {
        val ratio = ratioFor(paragraphs(400_000), 400_000)
        assertTrue(ratio < 2.0, "paragraphs regressed to ${"%.1f".format(ratio)}x")
    }

    // ------------------------------------------------- cost per block, not per char

    /**
     * How many times pagination reaches into the block list.
     *
     * Characters were only half the story. The other half is how often the block
     * *list* is walked, and a scan hidden behind `blocks.take(index)` costs nothing
     * a character counter can see. `AbstractList` implements its iterator on top of
     * `get`, so counting `get` counts every walk, however it was spelled.
     */
    private class CountingBlocks(
        private val backing: List<ContentBlock>,
    ) : AbstractList<ContentBlock>() {
        var reads = 0L
        override val size: Int get() = backing.size
        override fun get(index: Int): ContentBlock {
            reads++
            return backing[index]
        }
    }

    /** Block reads per block, paginating a chapter of [blockCount] paragraphs. */
    private fun readsPerBlock(blockCount: Int): Double {
        val one = prose(250)
        val blocks = CountingBlocks(
            (0 until blockCount).map { ContentBlock.Paragraph(listOf(InlineSpan(one))) },
        )
        val chapter = Chapter(
            index = 0, title = "Long", blocks = blocks,
            startCharOffset = 0, charCount = blockCount * one.length,
        )
        Paginator(CountingMeasurer()).paginate(chapter, viewport, settings)
        return blocks.reads.toDouble() / blockCount
    }

    @Test
    fun `a chapter of many blocks reads each block a bounded number of times`() {
        // `ChapterOpening.isChapterOpening` ended in `blocks.take(index).none { ... }`
        // and the paginator called it once per block, so block i copied and scanned i
        // blocks: 2,005 reads per block at 4,000 blocks. The same shape as the
        // substring bug of 2026-09-12, moved from characters to blocks, and reached
        // by the same books — a TXT or a reflowed PDF that is one long chapter.
        // Four after the fix: the text, the style, the loop, and the spacing.
        val perBlock = readsPerBlock(4_000)
        assertTrue(
            perBlock < 12.0,
            "read each block ${"%.0f".format(perBlock)} times to paginate it once",
        )
    }

    // ------------------------------------------------------ work that is not wanted

    @Test
    fun `a superseded pagination abandons the chapter instead of finishing it`() {
        // paginate is an ordinary function called inside withContext(Default), so
        // cancelling the coroutine around it does not stop it. A reader stepping the
        // type size four times had four layouts of the same long chapter competing
        // for the same cores, three of them already thrown away — which is the type
        // control appearing to do nothing and then settling on whichever finished
        // last rather than on what was asked for.
        val whole = CountingMeasurer()
        Paginator(whole).paginate(oneBlock(400_000), viewport, settings)

        val abandoned = CountingMeasurer()
        var pagesSoFar = 0
        assertFailsWith<CancellationException> {
            Paginator(abandoned).paginate(oneBlock(400_000), viewport, settings) {
                pagesSoFar++ < 2
            }
        }
        assertTrue(
            abandoned.charsMeasured < whole.charsMeasured / 10,
            "kept laying out ${abandoned.charsMeasured} of ${whole.charsMeasured} " +
                "characters after being superseded",
        )
    }

    @Test
    fun `the per-block cost does not grow as the chapter grows`() {
        // The signature of the quadratic, stated the same way the character test
        // states it: whatever the constant is, it must not depend on chapter length.
        val small = readsPerBlock(1_000)
        val large = readsPerBlock(4_000)
        assertTrue(
            large < small * 1.5,
            "block reads per block grew from ${"%.0f".format(small)} to ${"%.0f".format(large)}",
        )
    }
}
