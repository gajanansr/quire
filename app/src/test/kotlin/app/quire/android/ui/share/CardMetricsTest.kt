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
    }

    @Test
    fun `the content width is what the margins leave`() {
        val frame = CardMetrics.of(230f)
        assertEquals(frame.widthDp - frame.marginDp * 2, frame.contentWidthDp, TOLERANCE)
    }

    @Test
    fun `the card is 9 by 16`() {
        // Story proportions. Every platform the card is posted to expects them.
        val frame = CardMetrics.of(270f)
        assertEquals(480f, frame.heightDp, TOLERANCE)
    }

    @Test
    fun `the chrome cannot claim the whole card`() {
        // This replaces `the passage leaves room for the header and the footer`, which
        // compared the chrome against a `quoteHeightDp` fraction that no longer
        // exists — and was the problem. The passage's field is not a fraction of the
        // card: it is the weighted box left over once the header and the footer have
        // taken what they need, and `QuoteCard` fits the passage to the height that
        // box actually reports. A fraction saying what that height *would* be was a
        // second description of it, and when the two disagreed a line of the reader's
        // quotation went off the bottom of the PNG.
        //
        // What is still worth stating is that there is a field left at all. Six lines
        // of chrome and a gap, against the card inside its margins.
        val frame = CardMetrics.of(230f)
        val chrome = frame.labelSp * 1.3f * 5 + frame.wordmarkSp * 1.25f + frame.gapDp
        val inside = frame.heightDp - frame.marginDp * 2

        assertTrue(
            "${chrome}dp of chrome in a ${inside}dp card leaves nothing for the passage",
            chrome < inside / 2,
        )
    }

    private companion object {
        /** Floating point, on measurements nobody can see a hundredth of a dp of. */
        const val TOLERANCE = 0.001f
    }
}
