package app.folio.android.ui.share

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

    /** A passage, sized to fit, and whether anything had to be dropped. */
    data class Fit(
        val text: String,
        val fontSizeSp: Float,
        val maxLines: Int,
        val truncated: Boolean,
    )

    /**
     * The tiers.
     *
     * Each is a length ceiling with the size and line budget that holds it on a card
     * of this shape. They step rather than scale continuously because a continuous
     * fit makes every card a slightly different size, and a set of cards that are
     * almost the same reads as sloppy where a set that is clearly different reads as
     * deliberate.
     */
    private val tiers = listOf(
        Tier(chars = 120, sizeSp = 23f, maxLines = 7),
        Tier(chars = 260, sizeSp = 19f, maxLines = 11),
        Tier(chars = 440, sizeSp = 16f, maxLines = 15),
        Tier(chars = MAX_CHARS, sizeSp = 13f, maxLines = 22),
    )

    private data class Tier(val chars: Int, val sizeSp: Float, val maxLines: Int)

    fun of(passage: String): Fit {
        val trimmed = passage.trim()
        val tier = tiers.firstOrNull { trimmed.length <= it.chars } ?: tiers.last()
        if (trimmed.length <= MAX_CHARS) {
            return Fit(trimmed, tier.sizeSp, tier.maxLines, truncated = false)
        }
        return Fit(
            text = ellipsised(trimmed, MAX_CHARS),
            fontSizeSp = tiers.last().sizeSp,
            maxLines = tiers.last().maxLines,
            truncated = true,
        )
    }

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
