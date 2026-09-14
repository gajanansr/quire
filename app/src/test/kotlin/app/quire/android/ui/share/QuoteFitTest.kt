package app.quire.android.ui.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * A shared passage arrives whole.
 *
 * The bug this exists to prevent, reported from a real phone: sharing a selection sent
 * the first sentence and a closing quotation mark, so the card looked complete and was
 * not. Two things were wrong — the card cut at 180 characters, and it closed the quote
 * after cutting. Silent truncation of somebody's chosen words is the worst failure this
 * feature can have.
 */
class QuoteFitTest {

    private fun words(count: Int): String =
        (1..count).joinToString(" ") { "word$it" }

    @Test
    fun `a short passage is left exactly as it is`() {
        val passage = "Bagel and cream cheese, classic combo."
        assertEquals(passage, QuoteFit.of(passage).text)
        assertFalse(QuoteFit.of(passage).truncated)
    }

    @Test
    fun `a passage of several paragraphs survives whole`() {
        // The reported case: three paragraphs selected, one sentence shared. Anything
        // inside the cap must come out byte for byte.
        val passage = buildString {
            append("‘Bay-gulls. That’s how you pronounce them, spelt b-a-g-e-l-s,’ Avinash said ")
            append("at the breakfast counter on 85 Broad Street.\n\n")
            append("Avinash, a batchmate of mine from IIMA, had also made it to Goldman Sachs. ")
            append("He knew a lot more than me about the way things worked in America.")
        }
        val fit = QuoteFit.of(passage)
        assertEquals(passage, fit.text)
        assertFalse("a 300-character passage was cut", fit.truncated)
    }

    @Test
    fun `type steps down as the passage grows`() {
        val sizes = listOf(60, 200, 350, 600).map { QuoteFit.of(words(it / 6)).fontSizeSp }
        assertEquals(
            "sizes did not decrease monotonically: $sizes",
            sizes.sortedDescending(),
            sizes,
        )
    }

    @Test
    fun `a longer passage is given more lines to use`() {
        assertTrue(
            QuoteFit.of(words(80)).maxLines > QuoteFit.of("A short one.").maxLines,
        )
    }

    @Test
    fun `past the cap the cut is visible`() {
        val fit = QuoteFit.of(words(400))
        assertTrue("an over-long passage was not marked truncated", fit.truncated)
        assertTrue("no ellipsis to show something was dropped", fit.text.endsWith("…"))
        assertTrue("still over the cap: ${fit.text.length}", fit.text.length <= QuoteFit.MAX_CHARS + 1)
    }

    @Test
    fun `a cut lands on a word boundary`() {
        // "the doughnut-sha…" reads as a rendering fault; a whole word reads as a
        // quotation that carries on elsewhere.
        val fit = QuoteFit.of(words(400))
        val lastWord = fit.text.removeSuffix("…").trimEnd().substringAfterLast(' ')
        assertTrue("cut mid-word: …$lastWord", lastWord.matches(Regex("word\\d+")))
    }

    @Test
    fun `a dangling comma is not left before the ellipsis`() {
        val passage = "x".repeat(680) + " tail, more words that will not fit at all here"
        val fit = QuoteFit.of(passage)
        assertFalse("left a comma hanging: ${fit.text.takeLast(12)}", fit.text.endsWith(",…"))
    }

    @Test
    fun `surrounding whitespace never counts against the passage`() {
        val fit = QuoteFit.of("   A quiet place to read.   ")
        assertEquals("A quiet place to read.", fit.text)
    }

    @Test
    fun `no passage is ever set on a line shorter than twenty characters`() {
        // The "too much zoomed" assertion, reported from a real phone. Under twenty
        // characters a line stops reading as a quotation and starts reading as an
        // enlarged screenshot — and a card is looked at full-bleed, about 1.8x the
        // size the sheet previews it at, which roughly doubles the effect.
        //
        // Every length is swept rather than the four tier ceilings, so this holds for
        // whatever the tiers are next changed to.
        (1..QuoteFit.MAX_CHARS).forEach { length ->
            val fit = QuoteFit.of(passage(length))
            val measure = QuoteFit.charactersPerLine(fit.fontSizeSp, WIDTH)
            assertTrue(
                "a $length-character passage is set at ${fit.fontSizeSp}sp, " +
                    "which is $measure characters to a line",
                measure >= QuoteFit.MIN_MEASURE,
            )
        }
    }

    @Test
    fun `every passage fits the lines it is given`() {
        // A tier that cannot hold the passages it is for ellipsises them, which is the
        // silent truncation this whole object exists to prevent — arriving by the back
        // door instead of through the cap. Three of the four original tiers failed
        // this: 260 characters at 19sp need 12.4 lines and were given 11.
        (1..QuoteFit.MAX_CHARS).forEach { length ->
            val fit = QuoteFit.of(passage(length))
            val measure = QuoteFit.charactersPerLine(fit.fontSizeSp, WIDTH)
            val needed = ceil(length / measure).toInt()
            assertTrue(
                "a $length-character passage needs $needed lines and is given ${fit.maxLines}",
                needed <= fit.maxLines,
            )
        }
    }

    @Test
    fun `the same passage is set twice as large on a card twice as wide`() {
        // "not at all responsive": the sizes used to be absolute sp, so the card was
        // one design in the 230dp preview and a different, magnified one in the
        // exported PNG. The line budget is a ratio and must not move at all.
        val text = words(40)
        val narrow = QuoteFit.of(text, 230f)
        val wide = QuoteFit.of(text, 460f)

        assertEquals(narrow.fontSizeSp * 2, wide.fontSizeSp, 0.001f)
        assertEquals(narrow.maxLines, wide.maxLines)
    }

    @Test
    fun `a short passage is not blown up to fill the card`() {
        // "font size should be less." A six-word passage takes the largest tier, so
        // the largest tier is what the reader was looking at when they said so.
        val fit = QuoteFit.of("Bagels, and how you pronounce them.", WIDTH)
        assertTrue("set at ${fit.fontSizeSp}sp on a ${WIDTH}dp card", fit.fontSizeSp < 20f)
    }

    /**
     * A passage of exactly [length] characters, broken into words.
     *
     * Never begins or ends on a space: [QuoteFit] trims, and a passage that trimmed
     * down to 699 characters would quietly test the tier below the one meant.
     */
    private fun passage(length: Int): String {
        val chars = CharArray(length) { if (it % 6 == 5) ' ' else 'a' }
        chars[0] = 'a'
        chars[length - 1] = 'a'
        return String(chars)
    }

    private companion object {
        /** The card the sheet previews on a typical phone. */
        const val WIDTH = QuoteFit.REFERENCE_WIDTH_DP
    }
}
