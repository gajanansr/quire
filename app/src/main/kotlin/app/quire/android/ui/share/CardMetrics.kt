package app.quire.android.ui.share

/**
 * The proportions of a share card, at whatever size it happens to be drawn.
 *
 * Every measurement here is a fraction of the card's width rather than a fixed dp.
 * The failure that forces it, reported from a real phone: "that image being formed is
 * too much zoomed and not at all responsive." The card was laid out in absolute dp and
 * sp picked by eye against the sheet's preview, which is about 230dp wide. The
 * exported PNG is 604px on the same phone, and it is looked at full-bleed — roughly
 * 1.8x the size everything was tuned at. Nothing in the card knew that, so the type
 * arrived magnified, and nothing adapted when the card was drawn at any other width.
 *
 * Holding the card in fractions makes the preview a true scale model of the export,
 * which is also the only way the two can be kept from disagreeing: they are one
 * composable, and now they are one set of proportions as well.
 *
 * The fractions are chosen so the frame is very nearly unchanged at today's ~230dp
 * preview — an 18dp margin becomes 19.6dp, an 11sp label 10.6sp. The card's *frame*
 * was never the complaint; what changes is that it now scales, and that the passage
 * inside it is sized from the same numbers (see [QuoteFit]).
 */
internal object CardMetrics {

    /** Story proportions. Every platform a card is posted to expects 9:16. */
    const val ASPECT = 16f / 9f

    /** The card's own margin, all four sides. */
    private const val MARGIN = 0.085f

    /** Title, author, chapter, and the footer's second line. */
    private const val LABEL = 0.046f

    /** The wordmark, which is the one piece of chrome that is meant to be seen. */
    private const val WORDMARK = 0.068f

    /** The streak card's numeral — the one thing on that card anybody reads. */
    private const val DISPLAY = 0.15f

    /** The gap between the chapter label and the wordmark beneath it. */
    private const val GAP = 0.034f

    // There is no QUOTE_HEIGHT here any more.
    //
    // It used to say what share of the card's height the passage might occupy — 0.58
    // — and [QuoteFit] sized the passage against it. But the passage does not live in
    // a box of that height: it lives in a weighted box between the header and the
    // footer, and Compose already knows exactly how tall that came out. The fraction
    // was a *second* description of a height something else had measured, and the two
    // could disagree by a line. When they did, the line went off the bottom of a PNG
    // that cannot re-flow, and a reader's quotation stopped mid-sentence.
    //
    // The field now reports its own size (see `QuoteCard`), so the estimate has
    // nothing left to be wrong about.

    /** One card's measurements, in dp (and sp at the default font scale). */
    data class Frame(
        val widthDp: Float,
        val heightDp: Float,
        val marginDp: Float,
        val gapDp: Float,
        val labelSp: Float,
        val wordmarkSp: Float,
        val displaySp: Float,
        val contentWidthDp: Float,
    )

    fun of(widthDp: Float): Frame {
        val margin = widthDp * MARGIN
        return Frame(
            widthDp = widthDp,
            heightDp = widthDp * ASPECT,
            marginDp = margin,
            gapDp = widthDp * GAP,
            labelSp = widthDp * LABEL,
            wordmarkSp = widthDp * WORDMARK,
            displaySp = widthDp * DISPLAY,
            contentWidthDp = widthDp - margin * 2,
        )
    }
}
