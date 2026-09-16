package app.quire.android.ui.share

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.quire.android.ui.theme.ReaderFont

/**
 * The one place the passage on a share card is described.
 *
 * The same shape as `readerTextStyle`, and for the same reason. Whatever decides how
 * large the passage may be has to lay it out; whatever draws it lays it out again. If
 * those two are separate descriptions they drift, and the way this one drifts is
 * visible: the card is a fixed rectangle, so a renderer that takes one line more than
 * the fitter allowed for has nowhere to put it and the line is simply gone off the
 * bottom of a PNG nobody can re-flow.
 *
 * [size] is in dp, deliberately, and is converted here rather than at any call site.
 * A card is a picture with fixed proportions; if an accessibility font scale grew the
 * passage but not the card, the quote would run off the edge of an export the reader
 * cannot fix. `Dp.toSp()` divides by that scale, so what is rendered is the number of
 * pixels the card's own proportions asked for.
 *
 * Colour is not here. A colour is paint and not layout — it moves no glyph — so the
 * palette stays a parameter of the drawing and the fitter never has to know it. The
 * reader's text makes the same claim about highlights and `MeasureMatchesRenderTest`
 * proves it rather than trusting it.
 */
fun quoteTextStyle(font: ReaderFont, size: Float, density: Density): TextStyle =
    with(density) {
        TextStyle(
            fontFamily = font.family(),
            // A quote wants an italic, but only where there is a real one to use.
            // See [QuoteFit.isItalic].
            fontStyle = if (QuoteFit.isItalic(font)) FontStyle.Italic else FontStyle.Normal,
            fontSize = size.dp.toSp(),
            lineHeight = (size * QuoteFit.LINE_HEIGHT).dp.toSp(),
            // The same two settings the reader's own text uses, for the same reasons:
            // paragraph-wide breaking rather than greedy line filling, and hyphenation
            // so a long word breaks instead of opening a hole in the measure.
            hyphens = Hyphens.Auto,
            lineBreak = LineBreak.Paragraph,
        )
    }

/**
 * How large a passage is set on a share card.
 *
 * A card is a fixed 9:16 rectangle and a passage can be four words or four hundred,
 * so one size cannot serve both. The size is therefore computed from the passage —
 * the largest it can be set at and still fit the field it is given — and capped at
 * both ends, so a two-word quote is not blown up into a billboard and a long one
 * stays legible in a feed.
 *
 * **This replaces a table of five tiers keyed on character count.** The tiers were an
 * improvement on the fixed 23/19/16/13sp before them, but they shared the fault that
 * made those wrong: they *estimated*. A tier named a number of characters it wanted on
 * a line, and the size fell out of an average character advance — one number, 0.48 em,
 * for four typefaces with different widths, and for a passage that might be all short
 * words or all long ones. An estimate that is close is still a second opinion, and the
 * card had two: the estimate that chose the size and the layout that drew it. Reported
 * from a real phone, twice, as type that was too large; the second report came after
 * the tiers had already shipped.
 *
 * Now there is one opinion. [of] is handed a way to lay text out and uses it, so the
 * chosen size is one that was *observed* to fit, in the reader's own face, with the
 * reader's own words. The card passes a real text measurer wired to [quoteTextStyle];
 * a test passes whatever it needs to state an invariant. Nothing here knows about
 * characters per line, because nothing here has to guess.
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

    /** Leading, as a multiple of the type size. Used by [quoteTextStyle] and only there. */
    const val LINE_HEIGHT = 1.35f

    /**
     * The largest a passage is ever set, as a fraction of the measure it is set across.
     *
     * This is the number the reader was complaining about. The tiers it replaces put a
     * short quote at 7.9% of the card's width — on the 1208px export that is a 95px
     * body, which is not a quotation, it is a headline. Editorial quote cards sit
     * nearer 4–5% of their width; 5.2% of the card is 6.3% of the measure inside its
     * margins, which is where this lands. A short quote is now set about a third
     * smaller than it was.
     *
     * A cap rather than a fit, because a short passage has no natural size: three
     * words would fill a 9:16 card at an absurd height if nothing stopped them. The
     * card centres them in their field with air either side instead, which is what a
     * quotation looks like.
     */
    const val MAX_SIZE = 0.063f

    /**
     * The smallest, as a fraction of the same measure.
     *
     * The floor that keeps a long passage readable at the size a card is actually
     * looked at. It is normally the [MAX_CHARS] cap that binds first — 700 characters
     * in an average face come to rest around 5.2% of the measure, above this floor —
     * so this is here for the passages that behave worse than average: one long German
     * compound per line, or a face with a wide advance. Those are exactly the cases an
     * estimate used to get wrong in the direction that clips.
     */
    const val MIN_SIZE = 0.036f

    /**
     * How many sizes are tried between the two caps.
     *
     * Fine enough that the steps are invisible — about a third of a point apart on the
     * sheet's preview — and coarse enough that fitting a passage costs a bounded
     * number of layouts. The previous version stepped in five visible jumps on the
     * theory that "a set of cards that are almost the same reads as sloppy". That is
     * true of a deliberate style and false of a fit: what reads as sloppy is a
     * passage that does not fill its card, or one that is cut because the tier above
     * it was one step too big.
     */
    const val STEPS = 16

    /** The space a card gives its passage, in dp: whatever the layout actually left. */
    data class Field(val widthDp: Float, val heightDp: Float)

    /** What one attempt at laying the passage out came to. */
    data class Laid(val heightDp: Float, val lines: Int)

    /**
     * A way to lay a passage out.
     *
     * The seam that makes this object testable without a device and honest with one:
     * the card hands it Compose's own measurer wired to [quoteTextStyle], which is the
     * style it then draws with, so "will it fit" is answered by the thing that decides
     * whether it fits.
     */
    fun interface Measure {
        /** [text] set at [size] dp across the field's width. */
        fun layout(text: String, size: Float): Laid
    }

    /** A passage, sized to fit, and whether anything had to be dropped. */
    data class Fit(
        /** Exactly the string to draw, quotation marks and all — see [of]. */
        val text: String,
        /** The size in dp, which is the size in sp at the default font scale. */
        val size: Float,
        val maxLines: Int,
        val truncated: Boolean,
    )

    /**
     * Whether a passage set in [font] should be italic.
     *
     * A quote wants an italic, but only where there is one to use. Source Serif ships
     * `source_serif_italic` and the platform's own family has a real italic face;
     * Lora and Work Sans are bundled upright only, and Compose fills that gap by
     * shearing the upright. A synthetic oblique at card sizes reads as a rendering
     * fault rather than as a quotation, which is worse than an upright passage
     * already sitting inside quotation marks.
     */
    fun isItalic(font: ReaderFont): Boolean = when (font) {
        ReaderFont.SERIF, ReaderFont.SYSTEM -> true
        ReaderFont.LORA, ReaderFont.SANS -> false
    }

    /**
     * Sizes [passage] to [field].
     *
     * [Fit.text] comes back with its quotation marks already on it. They are part of
     * what gets laid out — an opening curly quote and a closing one are two glyphs
     * that can push a line over — so the fitter measures the string the card will
     * draw, not the string it was given.
     *
     * Only when the floor cannot hold the passage is anything cut, and then visibly:
     * an ellipsis inside the quotation marks, on a word boundary, never mid-word.
     */
    fun of(passage: String, field: Field, measure: Measure): Fit {
        val trimmed = passage.trim()
        val capped = trimmed.length > MAX_CHARS
        val body = if (capped) ellipsised(trimmed, MAX_CHARS) else trimmed

        sizes(field).forEach { size ->
            val shown = quoted(body)
            val laid = measure.layout(shown, size)
            if (laid.heightDp <= field.heightDp) {
                return Fit(shown, size, laid.lines, capped)
            }
        }

        // Even the floor cannot hold it. Cut until it does — the longest prefix that
        // fits, found by halving rather than by stepping a character at a time, since
        // each attempt is a full text layout.
        val floor = field.widthDp * MIN_SIZE
        var low = 0
        var high = body.length
        var best = ellipsised(body, 1)
        var bestLaid = measure.layout(quoted(best), floor)
        while (low <= high) {
            val middle = (low + high) / 2
            val candidate = ellipsised(body, middle)
            val laid = measure.layout(quoted(candidate), floor)
            if (laid.heightDp <= field.heightDp) {
                best = candidate
                bestLaid = laid
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return Fit(quoted(best), floor, bestLaid.lines, true)
    }

    /** The sizes tried, largest first: the first one that fits is the one used. */
    fun sizes(field: Field): List<Float> {
        val largest = field.widthDp * MAX_SIZE
        val smallest = field.widthDp * MIN_SIZE
        return (0 until STEPS).map { step ->
            largest - (largest - smallest) * step / (STEPS - 1)
        }
    }

    /** The passage as the card draws it. */
    private fun quoted(body: String): String = "“$body”"

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
