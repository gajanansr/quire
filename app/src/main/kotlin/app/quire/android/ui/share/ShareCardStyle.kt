package app.quire.android.ui.share

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import app.quire.android.ui.library.CoverGradient
import app.quire.android.ui.theme.QuireColors
import app.quire.android.ui.theme.QuirePalettes

/**
 * How a share card is dressed.
 *
 * Six looks: the book's own cover colours, and Quire's five palettes. Deliberately
 * the five the app already ships rather than a set invented for this one screen —
 * a share card is the most public thing Quire produces, and it should look like the
 * product it came from. It also means the palettes are maintained in one place: a
 * change to Night reaches the card without anyone remembering to make it.
 *
 * The style is chosen on the share sheet and is independent of the reader's own
 * theme, which is the point. Someone reading at night still wants the option of
 * posting a light card.
 */
enum class ShareCardStyle(val label: String) {
    COVER("Cover"),
    PAPER("Paper"),
    SEPIA("Sepia"),
    EINK("E-ink"),
    NIGHT("Night"),
    BLACK("Black");

    /**
     * @param bookId the book whose cover [COVER] should use. Null for a card that
     *   belongs to no book — a reading streak — where [COVER] has nothing to draw
     *   and falls back rather than inventing a swatch.
     */
    fun palette(bookId: String?): CardPalette = when (this) {
        COVER -> bookId?.let(::coverPalette) ?: NIGHT.palette(null)
        PAPER -> from(QuirePalettes.Paper)
        SEPIA -> from(QuirePalettes.Sepia)
        EINK -> from(QuirePalettes.Eink)
        NIGHT -> from(QuirePalettes.Night)
        BLACK -> from(QuirePalettes.Black)
    }

    private fun from(colors: QuireColors) = CardPalette(
        background = listOf(colors.readerBg),
        ink = colors.ink,
        muted = colors.muted,
    )

    /**
     * The book's gradient, darkened.
     *
     * Not decoration. White type on the lightest of the six swatches measures
     * 4.24:1, which is under the 4.5:1 a passage of text needs — the card would be
     * legible on five books and not on the sixth, and nobody would find out until
     * they posted one. A flat scrim over the whole gradient fixes every swatch at
     * once and costs nothing a reader would notice.
     */
    private fun coverPalette(bookId: String): CardPalette {
        val (start, end) = CoverGradient.colorsFor(bookId)
        return CardPalette(
            background = listOf(start.darkenedBy(SCRIM), end.darkenedBy(SCRIM)),
            ink = Color.White,
            muted = Color.White.copy(alpha = 0.72f),
        )
    }

    private companion object {
        /** How far the cover gradient is taken down before type is set on it. */
        const val SCRIM = 0.22f
    }
}

/**
 * The colours one card is drawn in.
 *
 * Background is a list because a cover is a gradient and a theme is flat, and the
 * contrast test wants every colour the type might sit on — checking only the first
 * would pass a gradient that ends somewhere unreadable.
 */
data class CardPalette(
    val background: List<Color>,
    val ink: Color,
    val muted: Color,
) {
    val brush: Brush
        get() = background.singleOrNull()?.let(::SolidColor)
            ?: Brush.linearGradient(background)
}

/** Composites black at [amount] over a colour. */
private fun Color.darkenedBy(amount: Float): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)
