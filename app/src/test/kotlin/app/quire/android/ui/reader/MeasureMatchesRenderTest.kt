package app.quire.android.ui.reader

import android.app.Application
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.paginate.BlockStyles
import app.quire.core.paginate.TypographySettings
import androidx.compose.ui.graphics.Color
import app.quire.android.ui.theme.ReaderFont
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pagination and drawing must lay text out identically.
 *
 * The paginator decides how many lines fit a page. If the Reader then breaks the
 * same text into more lines than that, the surplus is clipped off the bottom — a
 * sentence cut in half, and no error raised anywhere. It is the quietest failure in
 * the app and it happened three separate ways: the measurer set weight and slant but
 * no alignment while the Reader justified, a level-one heading was measured at 1.6x
 * and drawn at 1.4x, and a blockquote was measured at full width and drawn indented.
 *
 * Each of those was a second description of the same thing. There is one now, and
 * this asserts both sides agree for every kind of block Quire sets.
 */
@OptIn(ExperimentalTextApi::class)
@RunWith(RobolectricTestRunner::class)
class MeasureMatchesRenderTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val density = Density(2.75f)

    private val measurer by lazy {
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(app),
            defaultDensity = density,
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    }

    private val prose = "Distributed systems are a collection of independent " +
        "computers that appear to their users as a single coherent system. The " +
        "consequences of this definition are far reaching, and they shape every " +
        "design decision that follows in this book."

    private fun settings(justify: Boolean) = TypographySettings(
        fontSizeSp = 19f, lineHeightMultiple = 1.55f, justify = justify,
        pixelsPerSp = with(density) { 1.sp.toPx() },
    )

    /** Lines the paginator believes this block takes at this width. */
    private fun measuredLines(block: ContentBlock, text: String, justify: Boolean, width: Float): Int {
        val style = BlockStyles.of(block, settings(justify))
        val quire = ComposeTextMeasurer(measurer, density, ReaderFont.SERIF.family())
        return quire.measure(text, style, width - style.indentPx).lineCount
    }

    /** Lines the Reader actually draws, laid out with the same shared style. */
    private fun drawnLines(block: ContentBlock, text: String, justify: Boolean, width: Float): Int {
        val style = BlockStyles.of(block, settings(justify))
        return measurer.measure(
            text = text,
            style = readerTextStyle(style, ReaderFont.SERIF.family(), density),
            constraints = Constraints(maxWidth = (width - style.indentPx).toInt()),
        ).lineCount
    }

    private fun assertAgrees(block: ContentBlock, justify: Boolean, width: Float = 900f) {
        val text = (block as? ContentBlock.Paragraph)?.spans?.joinToString("") { it.text }
            ?: prose
        assertEquals(
            "measured and drawn line counts differ for ${block::class.simpleName}, " +
                "justify=$justify",
            measuredLines(block, text, justify, width),
            drawnLines(block, text, justify, width),
        )
    }

    @Test
    fun `a justified paragraph measures as it draws`() {
        // The drift that actually clipped a reader's page: justification moves line
        // breaks, and only one side knew about it.
        assertAgrees(ContentBlock.Paragraph(listOf(InlineSpan(prose))), justify = true)
    }

    @Test
    fun `a ragged-right paragraph measures as it draws`() {
        assertAgrees(ContentBlock.Paragraph(listOf(InlineSpan(prose))), justify = false)
    }

    @Test
    fun `a heading measures as it draws`() {
        // Measured at 1.6x the body size, drawn at 1.4x.
        assertAgrees(ContentBlock.Heading(1, listOf(InlineSpan("The Weight of Silence"))), false)
    }

    @Test
    fun `a quotation measures at the width it is drawn in`() {
        // Set in from the margin, so it has a narrower measure and takes more lines.
        assertAgrees(ContentBlock.BlockQuote(listOf(InlineSpan(prose))), justify = true)
    }

    @Test
    fun `they agree at every type size the stepper offers`() {
        val block = ContentBlock.Paragraph(listOf(InlineSpan(prose)))
        var size = ReaderPreferences.MIN_SIZE
        while (size <= ReaderPreferences.MAX_SIZE) {
            val s = TypographySettings(
                fontSizeSp = size, lineHeightMultiple = 1.55f, justify = true,
                pixelsPerSp = with(density) { 1.sp.toPx() },
            )
            val style = BlockStyles.of(block, s)
            val quire = ComposeTextMeasurer(measurer, density, ReaderFont.SERIF.family())
            val drawn = measurer.measure(
                text = prose,
                style = readerTextStyle(style, ReaderFont.SERIF.family(), density),
                constraints = Constraints(maxWidth = 900),
            ).lineCount
            assertEquals(
                "differ at ${size}sp",
                quire.measure(prose, style, 900f).lineCount,
                drawn,
            )
            size += ReaderPreferences.STEP
        }
    }

    /**
     * A highlight cannot move a line break.
     *
     * This is the assumption the whole highlight feature rests on. The paginator has
     * no idea which passages are marked — it measures a chapter, not a reader — so
     * the drawn page carries spans the measured one never saw. That is only safe
     * because a mark changes no metric. The day someone reaches for a bolder
     * highlight, or an underline, or a little padding, this test fails instead of
     * pages quietly losing their last line again.
     *
     * Re-proved when a mark stopped being only a background. A highlighted run is now
     * also *recoloured*, so the drawn page carries two properties the measured one
     * never saw rather than one, and "a background cannot move a break" is no longer
     * the whole of the argument. Both are asserted here, together and separately, on
     * a paragraph and on the blockquote that is the reason the colour is there.
     */
    private fun assertMarkMovesNothing(block: ContentBlock, mark: ReaderMark, what: String) {
        val width = 900f
        val style = BlockStyles.of(block, settings(justify = true))
        val constraints = Constraints(maxWidth = (width - style.indentPx).toInt())
        val textStyle = readerTextStyle(style, ReaderFont.SERIF.family(), density)

        val plain = measurer.measure(
            text = readerText(prose, style),
            style = textStyle,
            constraints = constraints,
        )
        val marked = measurer.measure(
            text = readerText(prose, style, marks = listOf(mark)),
            style = textStyle,
            constraints = constraints,
        )

        assertEquals("$what changed the line count", plain.lineCount, marked.lineCount)
        assertEquals("$what changed the block's height", plain.size.height, marked.size.height)
        repeat(plain.lineCount) { line ->
            assertEquals(
                "$what moved the break on line $line",
                plain.getLineEnd(line),
                marked.getLineEnd(line),
            )
        }
    }

    /** A mark spanning a line break, which is where a metric change would show. */
    private val acrossABreak = 40..120

    @Test
    fun `a highlighted paragraph breaks exactly where the unhighlighted one does`() {
        assertMarkMovesNothing(
            ContentBlock.Paragraph(listOf(InlineSpan(prose))),
            ReaderMark(acrossABreak, Color.Yellow, Color.Black),
            "a highlight",
        )
    }

    @Test
    fun `recolouring a marked run moves no break either`() {
        // The half that is new. A colour is paint and not layout, which is exactly
        // the kind of claim that is true until someone reaches for a span property
        // that is not — so it is asserted on its own, with no background in the way
        // to hide a change.
        assertMarkMovesNothing(
            ContentBlock.Paragraph(listOf(InlineSpan(prose))),
            ReaderMark(acrossABreak, Color.Transparent, Color.Red),
            "recolouring a run",
        )
    }

    @Test
    fun `a highlighted blockquote breaks where the unhighlighted one does`() {
        // The block the mark's ink exists for: a quotation is set in `muted`, and a
        // marked run inside it is set in full ink instead. It is also the narrowest
        // measure on the page, so a metric change shows up here first.
        assertMarkMovesNothing(
            ContentBlock.BlockQuote(listOf(InlineSpan(prose))),
            ReaderMark(acrossABreak, Color.Yellow, Color.Black),
            "a highlighted quotation",
        )
    }

    @Test
    fun `the measurer is given no marks, because it has none to give`() {
        // The default that makes the above safe by construction: ComposeTextMeasurer
        // calls readerText with one argument, and a chapter it paginates has no
        // reader attached to it.
        val block = ContentBlock.Paragraph(listOf(InlineSpan(prose)))
        val style = BlockStyles.of(block, settings(justify = true))
        assertEquals(readerText(prose, style).spanStyles, emptyList<Any>())
    }
}
