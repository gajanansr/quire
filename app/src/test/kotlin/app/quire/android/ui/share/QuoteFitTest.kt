package app.quire.android.ui.share

import app.quire.android.ui.theme.ReaderFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

/**
 * How large a passage is set, and that a shared passage arrives whole.
 *
 * Two reports from a real phone are behind this file. The first: three paragraphs
 * selected, one sentence shared — the card cut at 180 characters and then closed the
 * quotation mark, so a truncated passage looked complete. The second, twice: the type
 * is too large. The second report arrived *after* a fix for it had shipped, which is
 * why the arithmetic that used to choose the size is gone entirely. Nothing here
 * estimates a character width any more; [QuoteFit] is handed a way to lay text out and
 * uses it.
 *
 * These tests state the invariants with a stand-in layout, so they hold whatever the
 * caps are next moved to. That the *real* layout agrees — in each of the four reading
 * faces, with the card's own style — is `CardTypeMatchesDrawTest`.
 */
class QuoteFitTest {

    private fun words(count: Int): String =
        (1..count).joinToString(" ") { "word$it" }

    /**
     * A stand-in for Compose's text layout: every glyph the same width, greedy wrap.
     *
     * Deliberately not the thing the card uses. These tests are about the rule —
     * biggest size that fits, capped at both ends, cut only when the floor cannot hold
     * it — and a rule that only holds for one particular font is not a rule. The real
     * measurer's agreement is asserted elsewhere, against the style the card draws.
     */
    private fun ruler(field: QuoteFit.Field, advance: Float = 0.5f) =
        QuoteFit.Measure { text, size ->
            val perLine = floor(field.widthDp / (size * advance)).toInt().coerceAtLeast(1)
            val lines = text.split('\n').sumOf { paragraph ->
                ceil(paragraph.length.coerceAtLeast(1) / perLine.toFloat()).toInt()
            }
            QuoteFit.Laid(heightDp = lines * size * QuoteFit.LINE_HEIGHT, lines = lines)
        }

    private fun fit(passage: String, field: QuoteFit.Field = FIELD, advance: Float = 0.5f) =
        QuoteFit.of(passage, field, ruler(field, advance))

    /** The passage itself, without the quotation marks the card draws around it. */
    private fun QuoteFit.Fit.body(): String = text.removePrefix("“").removeSuffix("”")

    @Test
    fun `a short passage is left exactly as it is`() {
        val passage = "Bagel and cream cheese, classic combo."
        assertEquals(passage, fit(passage).body())
        assertFalse(fit(passage).truncated)
    }

    @Test
    fun `a passage of several paragraphs survives whole`() {
        // The first report: three paragraphs selected, one sentence shared. Anything
        // inside the cap must come out byte for byte.
        val passage = buildString {
            append("‘Bay-gulls. That’s how you pronounce them, spelt b-a-g-e-l-s,’ Avinash said ")
            append("at the breakfast counter on 85 Broad Street.\n\n")
            append("Avinash, a batchmate of mine from IIMA, had also made it to Goldman Sachs. ")
            append("He knew a lot more than me about the way things worked in America.")
        }
        val fitted = fit(passage)
        assertEquals(passage, fitted.body())
        assertFalse("a 300-character passage was cut", fitted.truncated)
    }

    @Test
    fun `the passage that is measured is the passage that is drawn`() {
        // Including its quotation marks. They are two more glyphs on the first and
        // last lines and they can push a line over; a fitter that measured the bare
        // passage and handed the card a decorated one would be back to two
        // descriptions of one thing, which is how this card clipped before.
        val fitted = fit("A quiet place to read.")
        assertTrue("no opening quotation mark: ${fitted.text}", fitted.text.startsWith("“"))
        assertTrue("no closing quotation mark: ${fitted.text}", fitted.text.endsWith("”"))
    }

    @Test
    fun `nothing is ever set larger than the cap`() {
        // "font size should be less", twice. A short passage has no natural size — two
        // words would fill a 9:16 card at an absurd height — so the cap is the number
        // that was actually wrong, and it is what this pins.
        (1..QuoteFit.MAX_CHARS).forEach { length ->
            val fitted = fit(passage(length))
            assertTrue(
                "a $length-character passage is set at ${fitted.size}dp",
                fitted.size <= FIELD.widthDp * QuoteFit.MAX_SIZE + TOLERANCE,
            )
        }
    }

    @Test
    fun `a short quote is set a third smaller than the tiers set it`() {
        // The regression, in the units the reader was looking at. The tier table this
        // replaces set a short passage at 18.1dp on the sheet's 230dp card — 7.9% of
        // the card's width, a 95px body on the 1208px export. This is the assertion
        // that fails if anyone reaches for those numbers again.
        val fitted = fit("Bagels, and how you pronounce them.")
        assertTrue(
            "a short quote is still set at ${fitted.size}dp on a ${FIELD.widthDp}dp measure",
            fitted.size <= 12.5f,
        )
    }

    @Test
    fun `nothing is ever set smaller than the floor`() {
        // A card is looked at in a feed. Past this it is a picture of some grey.
        val fitted = fit(words(400))
        assertTrue(
            "set at ${fitted.size}dp, below the ${FIELD.widthDp * QuoteFit.MIN_SIZE}dp floor",
            fitted.size >= FIELD.widthDp * QuoteFit.MIN_SIZE - TOLERANCE,
        )
    }

    @Test
    fun `the size is computed from the passage, not fixed`() {
        val sizes = listOf(20, 120, 300, 500, 700).map { fit(passage(it)).size }
        assertEquals(
            "sizes did not decrease as the passage grew: $sizes",
            sizes.sortedDescending(),
            sizes,
        )
        assertTrue(
            "a 700-character passage is set at the same size as a 20-character one: $sizes",
            sizes.last() < sizes.first(),
        )
    }

    @Test
    fun `no passage overflows the field it was fitted to`() {
        // The invariant the whole object exists for. A card is a fixed rectangle and a
        // PNG cannot re-flow: a line that does not fit is not scrolled, it is gone.
        (1..QuoteFit.MAX_CHARS).forEach { length ->
            val fitted = fit(passage(length))
            val laid = ruler(FIELD).layout(fitted.text, fitted.size)
            assertTrue(
                "a $length-character passage is ${laid.heightDp}dp in a ${FIELD.heightDp}dp field",
                laid.heightDp <= FIELD.heightDp + TOLERANCE,
            )
            assertEquals(
                "the line budget disagrees with the layout at $length characters",
                laid.lines,
                fitted.maxLines,
            )
        }
    }

    @Test
    fun `a passage too long for the floor is cut, visibly`() {
        // A face wide enough that even the floor cannot hold 700 characters. This is
        // the case an estimate used to get wrong in the direction that clips: the
        // words were dropped off the bottom of the card with nothing to say so.
        val fitted = fit(words(400), advance = 1.6f)
        assertTrue("an over-long passage was not marked truncated", fitted.truncated)
        assertTrue("no ellipsis to show something was dropped", fitted.body().endsWith("…"))

        val laid = ruler(FIELD, advance = 1.6f).layout(fitted.text, fitted.size)
        assertTrue(
            "the cut passage still overflows: ${laid.heightDp}dp in ${FIELD.heightDp}dp",
            laid.heightDp <= FIELD.heightDp + TOLERANCE,
        )
    }

    @Test
    fun `past the cap the cut is visible`() {
        val fitted = fit(words(400))
        assertTrue("an over-long passage was not marked truncated", fitted.truncated)
        assertTrue("no ellipsis to show something was dropped", fitted.body().endsWith("…"))
        assertTrue(
            "still over the cap: ${fitted.body().length}",
            fitted.body().length <= QuoteFit.MAX_CHARS + 1,
        )
    }

    @Test
    fun `a cut lands on a word boundary`() {
        // "the doughnut-sha…" reads as a rendering fault; a whole word reads as a
        // quotation that carries on elsewhere.
        val fitted = fit(words(400))
        val lastWord = fitted.body().removeSuffix("…").trimEnd().substringAfterLast(' ')
        assertTrue("cut mid-word: …$lastWord", lastWord.matches(Regex("word\\d+")))
    }

    @Test
    fun `a dangling comma is not left before the ellipsis`() {
        val passage = "x".repeat(680) + " tail, more words that will not fit at all here"
        assertFalse(
            "left a comma hanging: ${fit(passage).body().takeLast(12)}",
            fit(passage).body().endsWith(",…"),
        )
    }

    @Test
    fun `surrounding whitespace never counts against the passage`() {
        assertEquals("A quiet place to read.", fit("   A quiet place to read.   ").body())
    }

    @Test
    fun `the same passage is set twice as large on a card twice as wide`() {
        // "not at all responsive": the sizes used to be absolute sp, so the card was
        // one design in the 230dp preview and a different, magnified one in the
        // exported PNG. Both caps are fractions of the measure, so the whole ladder
        // scales and the preview stays a true scale model of the export.
        val text = words(40)
        val narrow = fit(text, FIELD)
        val wide = fit(text, QuoteFit.Field(FIELD.widthDp * 2, FIELD.heightDp * 2))

        assertEquals(narrow.size * 2, wide.size, 0.01f)
        assertEquals(narrow.maxLines, wide.maxLines)
    }

    @Test
    fun `a passage is only italic in a face that ships an italic`() {
        // The card is set in whatever the reader chose in the typography sheet, and
        // only two of those four faces have an italic of their own. Where there is
        // none Compose fills the gap by shearing the upright, and a synthetic oblique
        // at card sizes reads as a rendering fault rather than as a quotation — which
        // is worse than an upright quote inside quotation marks.
        assertTrue("Source Serif ships an italic", QuoteFit.isItalic(ReaderFont.SERIF))
        assertTrue("the platform face has a real italic", QuoteFit.isItalic(ReaderFont.SYSTEM))
        assertFalse("Lora is bundled upright only", QuoteFit.isItalic(ReaderFont.LORA))
        assertFalse("Work Sans is bundled upright only", QuoteFit.isItalic(ReaderFont.SANS))
    }

    /**
     * A passage of exactly [length] characters, broken into words.
     *
     * Never begins or ends on a space: [QuoteFit] trims, and a passage that trimmed
     * down to 699 characters would quietly test one side of the cap while claiming to
     * test the other.
     */
    private fun passage(length: Int): String {
        val chars = CharArray(length) { if (it % 6 == 5) ' ' else 'a' }
        chars[0] = 'a'
        chars[length - 1] = 'a'
        return String(chars)
    }

    private companion object {
        /**
         * The field the sheet's preview leaves for the passage on a typical phone.
         *
         * A card about 230dp wide, less its margins, and the height the weighted box
         * between the header and the footer comes to. Concrete numbers so the
         * arithmetic can be reasoned about; nothing in [QuoteFit] assumes them.
         */
        val FIELD = QuoteFit.Field(widthDp = 191f, heightDp = 230f)

        const val TOLERANCE = 0.001f
    }
}
