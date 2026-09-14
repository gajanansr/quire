package app.quire.android.ui.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A share card is the same design at every size it is drawn.
 *
 * The bug this exists to prevent, reported from a real phone: "that image being formed
 * is too much zoomed and not at all responsive." The card was laid out in fixed dp and
 * sp, chosen by eye against the sheet's preview — which is about 230dp wide. The
 * exported PNG is 604px on the same phone and is then looked at full-bleed, roughly
 * 1.8x the size everything was tuned at, and nothing in the card knew that. Holding
 * every measurement as a fraction of the card's width is what makes the preview a true
 * scale model of the export instead of a smaller, different card.
 */
class CardMetricsTest {

    @Test
    fun `every measurement is a fraction of the card's width`() {
        // The whole point: a card twice as wide is the same card, twice as large. If
        // any measurement stopped scaling it would be the one that looks wrong in the
        // export and right in the preview, which is the failure being fixed.
        val small = CardMetrics.of(230f)
        val large = CardMetrics.of(460f)

        assertEquals(small.heightDp * 2, large.heightDp, TOLERANCE)
        assertEquals(small.marginDp * 2, large.marginDp, TOLERANCE)
        assertEquals(small.gapDp * 2, large.gapDp, TOLERANCE)
        assertEquals(small.labelSp * 2, large.labelSp, TOLERANCE)
        assertEquals(small.wordmarkSp * 2, large.wordmarkSp, TOLERANCE)
        assertEquals(small.displaySp * 2, large.displaySp, TOLERANCE)
        assertEquals(small.contentWidthDp * 2, large.contentWidthDp, TOLERANCE)
        assertEquals(small.quoteHeightDp * 2, large.quoteHeightDp, TOLERANCE)
    }

    @Test
    fun `the content width is what the margins leave`() {
        val frame = CardMetrics.of(230f)
        assertEquals(frame.widthDp - frame.marginDp * 2, frame.contentWidthDp, TOLERANCE)
    }

    @Test
    fun `the card is 9 by 16`() {
        // Story proportions. Every platform the card is posted to expects them, and
        // the quote's line budget is derived from this height.
        val frame = CardMetrics.of(270f)
        assertEquals(480f, frame.heightDp, TOLERANCE)
    }

    @Test
    fun `the passage leaves room for the header and the footer`() {
        // The quote field is not allowed to claim the whole card. What is left after
        // the margins has to hold a two-line title, an author, a chapter label and the
        // wordmark with its own second line — six lines of chrome and a gap. Take too
        // much for the passage and the quote sits on top of the title, which is a
        // failure this card has had before.
        val frame = CardMetrics.of(230f)
        val leftOver = frame.heightDp - frame.marginDp * 2 - frame.quoteHeightDp
        val chrome = frame.labelSp * 1.3f * 5 + frame.wordmarkSp * 1.25f + frame.gapDp

        assertTrue(
            "only ${leftOver}dp left for ${chrome}dp of chrome",
            leftOver >= chrome,
        )
    }

    private companion object {
        /** Floating point, on measurements nobody can see a hundredth of a dp of. */
        const val TOLERANCE = 0.001f
    }
}
