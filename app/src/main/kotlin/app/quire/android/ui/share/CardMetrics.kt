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

    /** The gap between the chapter label and the wordmark beneath it. */
    private const val GAP = 0.034f

    /**
     * The share of the card's *height* the passage may occupy.
     *
     * What is left has to hold a two-line title, an author, a chapter label and the
     * wordmark with its own second line. Claim more than this for the passage and the
     * quote ends up touching the title above it — a failure this card has had before,
     * found on a device, when `SpaceBetween` ran out of free space to distribute.
     */
    private const val QUOTE_HEIGHT = 0.58f

    /** One card's measurements, in dp (and sp at the default font scale). */
    data class Frame(
        val widthDp: Float,
        val heightDp: Float,
        val marginDp: Float,
        val gapDp: Float,
        val labelSp: Float,
        val wordmarkSp: Float,
        val contentWidthDp: Float,
        val quoteHeightDp: Float,
    )

    fun of(widthDp: Float): Frame {
        val margin = widthDp * MARGIN
        val height = widthDp * ASPECT
        return Frame(
            widthDp = widthDp,
            heightDp = height,
            marginDp = margin,
            gapDp = widthDp * GAP,
            labelSp = widthDp * LABEL,
            wordmarkSp = widthDp * WORDMARK,
            contentWidthDp = widthDp - margin * 2,
            quoteHeightDp = height * QUOTE_HEIGHT,
        )
    }
}
