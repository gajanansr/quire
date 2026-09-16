package app.quire.android.ui.share

import android.app.Application
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.quire.android.ui.theme.ReaderFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The passage on a share card is fitted by the thing that draws it.
 *
 * `QuoteFitTest` states the rule against a stand-in layout. This asserts the rule
 * survives contact with the real one, in each of the four faces a reader can choose,
 * because the way this card has failed twice is a *second opinion* about how much text
 * fits: first four sizes picked by eye, then five tiers keyed on an average character
 * width of 0.48 em applied to four typefaces that do not share one. Both were close.
 * Close is enough to lose a line, and a share card is a fixed rectangle exported as a
 * PNG — a line that does not fit is not scrolled off, it is gone, and the reader's
 * quotation stops mid-sentence with nothing to say it did.
 *
 * The same shape as `MeasureMatchesRenderTest`, which holds this line for the reader's
 * own pages, and for the same reason.
 */
@OptIn(ExperimentalTextApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardTypeMatchesDrawTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val density = Density(2.75f)

    private val measurer by lazy {
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(app),
            defaultDensity = density,
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }

    /** The field the sheet's preview leaves for a passage on a typical phone. */
    private val field = QuoteFit.Field(widthDp = 191f, heightDp = 230f)

    private val widthPx get() = with(density) { field.widthDp.dp.roundToPx() }

    /** Exactly what `QuoteCard` passes: Compose's own layout, wired to the card's style. */
    private fun layoutIn(font: ReaderFont) = QuoteFit.Measure { text, size ->
        val layout = measurer.measure(
            text = text,
            style = quoteTextStyle(font, size, density),
            constraints = Constraints(maxWidth = widthPx),
        )
        QuoteFit.Laid(
            heightDp = with(density) { layout.size.height.toDp().value },
            lines = layout.lineCount,
        )
    }

    private fun prose(length: Int): String {
        val source = "Distributed systems are a collection of independent computers " +
            "that appear to their users as a single coherent system, and the " +
            "consequences of that definition reach every decision in this book. " +
            "Bagels, and how you pronounce them, are not among those decisions. "
        return buildString { while (this.length < length) append(source) }.take(length).trim()
    }

    @Test
    fun `no passage overflows the field, in any reading face`() {
        // The invariant, against the real layout engine. A tier table could not state
        // this: it derived a line budget from an estimate and then trusted it.
        ReaderFont.entries.forEach { font ->
            val measure = layoutIn(font)
            (1..QuoteFit.MAX_CHARS step 37).forEach { length ->
                val fit = QuoteFit.of(prose(length), field, measure)
                val drawn = measure.layout(fit.text, fit.size)
                assertTrue(
                    "$font: a $length-character passage draws ${drawn.heightDp}dp " +
                        "into a ${field.heightDp}dp field at ${fit.size}dp",
                    drawn.heightDp <= field.heightDp,
                )
                assertEquals(
                    "$font: the line budget disagrees with the layout at $length characters",
                    drawn.lines,
                    fit.maxLines,
                )
            }
        }
    }

    @Test
    fun `the passage is measured in the face the reader chose`() {
        // "same font that is selected while reading", and not only on the way out:
        // the face has to reach the *fitter*, because a face is how wide the words
        // are. A single average advance for four typefaces is the estimate this
        // replaces, and it is the reason a passage that fitted on paper did not fit
        // on the card.
        ReaderFont.entries.forEach { font ->
            assertEquals(
                "the card would measure $font in somebody else's face",
                font.family(),
                quoteTextStyle(font, 12f, density).fontFamily,
            )
        }
    }

    @Test
    fun `a face with a different advance lays the same passage out differently`() {
        // The observable half of the test above: if every face measured the same, the
        // fitter would not need to know which one it was given, and the old average
        // would have been good enough.
        val passage = prose(420)
        val heights = ReaderFont.entries.map { font ->
            layoutIn(font).layout("“$passage”", 10f).heightDp
        }
        assertTrue(
            "every reading face laid 420 characters out to the same height: $heights",
            heights.distinct().size > 1,
        )
    }

    @Test
    fun `a short quote is set a third smaller than the tiers set it`() {
        // The report, in the units the reader was looking at. The tier table set a
        // short passage at 18.1dp on the sheet's 230dp card: 7.9% of the card's width,
        // a 95px body on the 1208px export, which is a headline and not a quotation.
        ReaderFont.entries.forEach { font ->
            val fit = QuoteFit.of(
                "Bagels, and how you pronounce them.",
                field,
                layoutIn(font),
            )
            assertTrue(
                "$font sets a short quote at ${fit.size}dp on a ${field.widthDp}dp measure",
                fit.size <= 12.5f,
            )
        }
    }

    @Test
    fun `a long passage is set smaller than a short one, in every face`() {
        // Responsive to the passage, which is the other half of the report: "not at
        // all responsive". Asserted per face because the point at which a passage
        // stops fitting is a property of the face, not of a character count.
        ReaderFont.entries.forEach { font ->
            val measure = layoutIn(font)
            val short = QuoteFit.of(prose(40), field, measure).size
            val long = QuoteFit.of(prose(QuoteFit.MAX_CHARS), field, measure).size

            assertTrue(
                "$font sets 40 characters at ${short}dp and ${QuoteFit.MAX_CHARS} at ${long}dp",
                long < short,
            )
        }
    }
}
