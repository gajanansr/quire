package app.folio.android.ui.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
