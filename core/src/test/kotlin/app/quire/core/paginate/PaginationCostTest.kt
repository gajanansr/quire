package app.quire.core.paginate

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import kotlin.test.Test
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
}
