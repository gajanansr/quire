package app.quire.android.ui.share

import kotlin.math.floor

/**
 * How a passage is set on a share card.
 *
 * The card is a fixed 9:16 rectangle and a passage can be four words or four hundred,
 * so one type size cannot serve both. It used to: the quote was set at one size, cut
 * at 180 characters, and closed with a quotation mark — which made a truncated passage
 * look like a complete one. A reader who selected three paragraphs got the first
 * sentence and a closing quote, with nothing to say the rest had gone.
 *
 * So the type steps down as the passage grows, and only past the point where even the
 * smallest size cannot hold it does anything get cut — visibly, with an ellipsis
 * inside the quotation marks, and never mid-word.
 */
internal object QuoteFit {

    /**
     * The longest passage a card will carry.
     *
     * Past this the type would be too small to read in a feed, which defeats the
     * point of a picture. The words are not lost — the text share carries the passage
     * whole — but the card stops being the right way to send it.
     */
    const val MAX_CHARS = 700

    /**
     * The width the sheet draws its preview at on a typical phone.
     *
     * A default, not an assumption: the card asks for its real width and everything
     * below scales to it. It exists so the arithmetic can be reasoned about — and
     * tested — at one concrete size.
     */
    const val REFERENCE_WIDTH_DP = 230f

    /**
     * How wide one character is, as a fraction of the type size.
     *
     * An average advance across the four reading faces. It only has to be close: it
     * is used to pick a tier, not to lay out a line, and the real wrapping is done by
     * the text layout with a generous line budget on top of this estimate.
     */
    const val CHAR_EM = 0.48f

    /** Leading, as a multiple of the type size. Matches what the card sets. */
    const val LINE_HEIGHT = 1.35f

    /**
     * The shortest line a passage is ever set to.
     *
     * This is the "too much zoomed" number. Type large enough that a line holds under
     * twenty characters stops reading as a quotation and starts reading as a
     * screenshot that has been enlarged — and a card is looked at full-bleed, where
     * the effect roughly doubles. The old largest tier set 23sp on a 230dp card, which
     * is about seventeen characters to a line.
     */
    const val MIN_MEASURE = 20

    /** A passage, sized to fit, and whether anything had to be dropped. */
    data class Fit(
        val text: String,
        /** The type size in sp at the default font scale — which is to say, in dp. */
        val fontSizeSp: Float,
        val maxLines: Int,
        val truncated: Boolean,
    )

    /**
     * The tiers.
     *
     * Each is a length ceiling and a **measure** — the number of characters that tier
     * wants on a line. Not a type size: a size in sp is a statement about the phone,
     * and what the card needs is a statement about itself. The size falls out of the
     * measure and the card's own width, so the sheet's 230dp preview and the 604px
     * export are the same design at two scales rather than two different cards.
     *
     * That is the fix for what a reader on a real phone called "too much zoomed and
     * not at all responsive". The old tiers were absolute — 23/19/16/13sp — tuned by
     * eye against the small preview, and at 23sp a line on that card holds about
     * seventeen characters. Blown up full-bleed it reads as a magnified screenshot.
     * Three of those four tiers could not even hold the passages they were for: 260
     * characters at 19sp need 12.4 lines and were given 11.
     *
     * They step rather than scale continuously because a continuous fit makes every
     * card a slightly different size, and a set of cards that are almost the same
     * reads as sloppy where a set that is clearly different reads as deliberate. The
     * measures widen as the passage grows, which is also what makes both ends look
     * composed: a short passage gets a short, generous line and plenty of air; a long
     * one gets a denser measure and fills the field.
     */
    private val tiers = listOf(
        Tier(chars = 60, measure = 22),
        Tier(chars = 140, measure = 26),
        Tier(chars = 300, measure = 32),
        Tier(chars = 500, measure = 40),
        Tier(chars = MAX_CHARS, measure = 46),
    )

    private data class Tier(val chars: Int, val measure: Int)

    /** How many characters a line holds, at this size, on a card this wide. */
    fun charactersPerLine(fontSizeSp: Float, cardWidthDp: Float): Float =
        CardMetrics.of(cardWidthDp).contentWidthDp / (fontSizeSp * CHAR_EM)

    fun of(passage: String, cardWidthDp: Float = REFERENCE_WIDTH_DP): Fit {
        val trimmed = passage.trim()
        val truncated = trimmed.length > MAX_CHARS
        val text = if (truncated) ellipsised(trimmed, MAX_CHARS) else trimmed
        val tier = tiers.firstOrNull { text.length <= it.chars } ?: tiers.last()
        val size = sizeFor(tier.measure, cardWidthDp)
        return Fit(text, size, linesFor(size, cardWidthDp), truncated)
    }

    /** The size at which a line of this card holds [measure] characters. */
    private fun sizeFor(measure: Int, cardWidthDp: Float): Float =
        CardMetrics.of(cardWidthDp).contentWidthDp / (measure * CHAR_EM)

    /**
     * How many lines fit in the field the card gives the passage.
     *
     * Derived rather than written down beside the tier, because a hand-written line
     * budget is the thing that drifts: three of the four original tiers were given
     * fewer lines than their own longest passage needed, which turned the cap that
     * exists to make truncation visible into silent truncation by another route.
     */
    private fun linesFor(fontSizeSp: Float, cardWidthDp: Float): Int =
        floor(CardMetrics.of(cardWidthDp).quoteHeightDp / (fontSizeSp * LINE_HEIGHT)).toInt()

    /**
     * Cuts at the last word boundary before [limit] and marks the cut.
     *
     * Never mid-word: a passage ending "he picked up the doughnut-sha…" reads as a
     * rendering fault, where one ending on a whole word reads as a quotation.
     */
    private fun ellipsised(text: String, limit: Int): String {
        val window = text.take(limit)
        val lastSpace = window.lastIndexOf(' ')
        val body = if (lastSpace > limit / 2) window.take(lastSpace) else window
        return body.trimEnd { it == ',' || it == ';' || it.isWhitespace() } + "…"
    }
}
