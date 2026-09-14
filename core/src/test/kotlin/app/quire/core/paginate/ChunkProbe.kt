package app.quire.core.paginate

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import kotlin.test.Test

/** TEMPORARY probe: prints the numbers the chunk-size constant is chosen from. */
class ChunkProbe {

    private class CountingMeasurer(private val emWidth: Float = 0.5f) : TextMeasurer {
        var calls = 0
        var charsMeasured = 0L
        override fun measure(text: String, style: BlockStyle, widthPx: Float): Measured {
            calls++
            charsMeasured += text.length
            val perLine = (widthPx / (style.fontSizeSp * emWidth * PPS)).toInt().coerceAtLeast(1)
            val lineEnds = mutableListOf<Int>()
            var cursor = 0
            while (cursor < text.length) {
                cursor = (cursor + perLine).coerceAtMost(text.length)
                lineEnds += cursor
            }
            if (lineEnds.isEmpty()) lineEnds += 0
            return Measured(lineEnds.size * style.lineHeightPx, lineEnds)
        }
    }

    private companion object {
        const val PPS = 2.75f // density of a common 1080x2400 phone
    }

    // ReaderScreen: 26dp side padding, 26dp top, 40dp bottom, plus system bars.
    private val availableWidthPx = (393f - 52f) * PPS
    private val viewportHeightPx = (873f - 24f - 24f - 26f - 40f) * PPS

    private fun settings(sizeSp: Float) = TypographySettings(
        fontSizeSp = sizeSp, lineHeightMultiple = 1.55f, pixelsPerSp = PPS,
    )

    private fun prose(chars: Int) = buildString {
        while (length < chars) {
            append(
                "The coffee was terrible, which was the only thing about the morning " +
                    "that felt reliable. She read the sentence again and it still said " +
                    "what it had said the first time. ",
            )
        }
    }.take(chars)

    /** The Love Hypothesis, as measured on the device: 8,621 blocks, 565,896 chars. */
    private fun realBook(): Chapter {
        val blocks = ArrayList<ContentBlock>(8_621)
        val each = 565_896 / 8_621
        var total = 0
        repeat(8_621) {
            val text = prose(each)
            total += text.length
            blocks += ContentBlock.Paragraph(listOf(InlineSpan(text)))
        }
        return Chapter(0, null, blocks, 0, total)
    }

    @Test
    fun probe() {
        val book = realBook()
        println("BOOK blocks=${book.blocks.size} chars=${book.charCount}")
        for (size in listOf(15f, 19f, 24f)) {
            val s = settings(size)
            val width = Measure.widthPx(availableWidthPx, s)
            val m = CountingMeasurer()
            val pages = Paginator(m).paginate(book, Viewport(width, viewportHeightPx), s)
            val chars = pages.characterCount()
            println(
                "size=${size}sp width=${"%.0f".format(width)}px lines=${
                    (viewportHeightPx / s.bodyLineHeightPx).toInt()
                } pages=${pages.size} charsPerPage=${chars / pages.size} " +
                    "measured=${m.charsMeasured} ratio=${"%.2f".format(m.charsMeasured.toDouble() / chars)} " +
                    "calls=${m.calls}",
            )
        }
    }
}
