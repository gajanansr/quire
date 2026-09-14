package app.quire.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The colours a reader can mark a passage in.
 *
 * Five, and deliberately five. People colour-code — this one is a fact, that one is
 * something I disagree with, this one is a sentence I loved — and a set that cannot
 * hold those apart is one colour with extra taps. A set of twelve is a palette nobody
 * remembers the meaning of, and on E-ink (see [QuireHighlights]) five is already the
 * most the hardware can tell apart.
 *
 * What each is *for*, which is the part worth writing down:
 *
 * - [KEEP] — worth remembering. The plain highlighter, and the default.
 * - [FACT] — something to be able to cite later: a number, a date, a claim with
 *   evidence behind it.
 * - [DOUBT] — a disagreement, or a claim to go and check.
 * - [LOOK_UP] — a word, a name or a reference to follow up.
 * - [LOVELY] — a phrase loved for its own sake rather than for what it says.
 *
 * The [hue] is all a colour carries. Lightness, chroma and opacity belong to the
 * *theme*, so that all five sit at one weight on a given page and no choice is
 * quietly louder than another — and so that a highlight made on Paper and read on
 * Night is the reader's mark rather than a colour tuned for cream paper glaring off
 * a black one.
 *
 * Stored by [name]. The stored value is the *choice*, never a resolved colour: the
 * reader picks "Doubt", not `#F59AA1`, and the theme they are reading in decides what
 * that looks like today.
 */
enum class HighlightColour(val label: String, internal val hue: Double) {
    KEEP("Keep", 90.0),
    FACT("Fact", 150.0),
    DOUBT("Doubt", 15.0),
    LOOK_UP("Look up", 230.0),
    LOVELY("Lovely", 300.0);

    companion object {
        /**
         * Gold, because it is the only colour a highlight has ever been.
         *
         * `MIGRATION_7_8` seeds every existing row with this, so the update that
         * brings colours to the app changes nothing on anybody's page.
         */
        val DEFAULT = KEEP
    }
}

/**
 * Resolves a colour name read back from the database.
 *
 * A plain string, exactly as the theme name is, and the same failure applies: a value
 * this version does not know — a row written by a later build, a hand-edited backup —
 * must resolve to a real colour rather than to nothing. A span painted
 * `Color.Unspecified` is a highlight the reader made and can no longer see.
 */
fun highlightColourNamed(stored: String?): HighlightColour =
    HighlightColour.entries.firstOrNull { it.name == stored } ?: HighlightColour.DEFAULT

/**
 * How one theme paints a highlight.
 *
 * [alpha] is the whole answer to "less opacity": a highlight is a wash the page shows
 * through, not a block of colour with words on top of it. It is also what makes one
 * set of five work on five very different pages — a bright pigment mixed a quarter of
 * the way into a near-black page lands dark on its own, so Night needs no second
 * palette and cannot drift from Paper's.
 */
private data class HighlightWash(
    val lightness: Double,
    val chroma: Double,
    val alpha: Float,
)

/**
 * Highlight colour, resolved for the theme the reader is actually in.
 *
 * Separate from [QuireColors] because a highlight is not one token: it is five
 * choices times five themes, and the arithmetic that turns a choice into a colour is
 * the part worth testing. `QuireHighlightsTest` holds four properties at once — the
 * text on a mark clears 4.5:1, the mark is visibly not the page, no two marks look
 * alike, and on E-ink no mark has any colour in it at all.
 *
 * **E-ink is the interesting case.** Every token of that palette has chroma exactly
 * zero because an electrophoretic panel is greyscale hardware and cannot render
 * colour at all — so "five colours" cannot mean the same thing there. A colour
 * becomes a *tone*: five neutral greys, lightest first, spanning the only axis the
 * panel has. The band is not a taste decision. Its light end is as light as a mark
 * can be while still reading as a mark rather than as the page, and its dark end is
 * as dark as it can be while the ink on it still clears AA; five is what fits between
 * them with a step the eye can still resolve. The cost, stated rather than hidden:
 * on a colour page the five marks weigh the same and differ in hue, and on E-ink they
 * cannot, so they differ in weight and the reader learns the ramp instead.
 */
object QuireHighlights {

    /**
     * How dark each colour goes on E-ink, indexed by [HighlightColour.ordinal].
     *
     * Chosen so the *composite* — the wash over E-ink's own page — lands on an even
     * ramp from `#D0D0D0` to `#858585`. Stated as pigments rather than as the
     * finished greys so that the E-ink path is the same arithmetic as every other
     * theme's, which is what keeps one wash from being opaque while the rest are not.
     */
    private val EINK_TONES = listOf(0.794, 0.688, 0.574, 0.455, 0.327)

    /**
     * Light pages take a pale pigment at 42%; dark pages take a brighter one at 26%.
     *
     * The dark themes' pigment is *lighter* than the light themes', which reads
     * backwards until you remember what alpha does: on Night the wash is adding
     * light to a near-black page, and a dim pigment there would add nothing. 26%
     * rather than 42% because the same proportion of a bright colour over black is
     * the glare this is avoiding.
     */
    private fun washOf(theme: QuireThemeName): HighlightWash = when (theme) {
        QuireThemeName.PAPER, QuireThemeName.SEPIA -> HighlightWash(0.78, 0.11, 0.42f)
        QuireThemeName.EINK -> HighlightWash(0.0, 0.0, 0.55f)
        QuireThemeName.NIGHT, QuireThemeName.BLACK -> HighlightWash(0.72, 0.135, 0.26f)
    }

    /**
     * The OKLCH the wash is mixed from: lightness, chroma, hue.
     *
     * Exposed so the gamut test can read the raw channels. Chroma stays inside what
     * sRGB can show at this lightness for *every* hue in the set — [oklchToSrgb]
     * clamps rather than failing, and a clamped channel is a silent hue shift.
     */
    internal fun pigmentOf(
        theme: QuireThemeName,
        colour: HighlightColour,
    ): Triple<Double, Double, Double> {
        val wash = washOf(theme)
        return if (theme == QuireThemeName.EINK) {
            Triple(EINK_TONES[colour.ordinal], 0.0, 0.0)
        } else {
            Triple(wash.lightness, wash.chroma, colour.hue)
        }
    }

    /** The translucent wash a highlighted run is painted with. */
    fun tint(theme: QuireThemeName, colour: HighlightColour): Color {
        val (l, c, h) = pigmentOf(theme, colour)
        return oklch(l, c, h).copy(alpha = washOf(theme).alpha)
    }

    /**
     * The same wash already composited on the page — the colour a reader sees.
     *
     * What a swatch and a Bookmarks row draw, so the list and the page cannot
     * disagree about what "Doubt" looks like. Also what the contrast test measures,
     * because a translucent colour's own contrast ratio is meaningless.
     */
    fun over(theme: QuireThemeName, colour: HighlightColour): Color =
        tint(theme, colour).composited(QuirePalettes.of(theme).readerBg)
}

/** This colour painted on [ground], as it will actually be seen. */
internal fun Color.composited(ground: Color): Color = Color(
    red = red * alpha + ground.red * (1f - alpha),
    green = green * alpha + ground.green * (1f - alpha),
    blue = blue * alpha + ground.blue * (1f - alpha),
)
