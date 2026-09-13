package app.folio.android.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import app.folio.core.paginate.BlockStyle
import app.folio.core.paginate.ChapterOpening

/**
 * The one place a line of this book's text is described.
 *
 * Pagination measures text and the Reader draws it, and they must agree exactly:
 * the paginator decides how many lines fit a page, and if the renderer breaks the
 * same text into more lines than that, the surplus is simply clipped off the bottom
 * of the page. A reader sees a sentence cut in half and no error anywhere.
 *
 * They had drifted. The measurer set weight and slant and no alignment; the renderer
 * set alignment — justified — and no weight. Justification moves line breaks, so the
 * two disagreed about line counts on every justified paragraph. One function now
 * produces the style and both sides call it, which makes the drift impossible rather
 * than unlikely.
 *
 * Two settings here are what make reflowed text read as a book rather than as a web
 * page:
 *
 * - [LineBreak.Paragraph] weighs a paragraph's breaks as a whole instead of filling
 *   each line greedily, which is what a typesetter does and what removes the ragged,
 *   lurching measure that greedy breaking leaves.
 * - [Hyphens.Auto] lets long words break. Without it, justified text pulls the words
 *   on a line apart to reach the margin and opens the white "rivers" that run down a
 *   badly set page.
 */
fun readerTextStyle(
    style: BlockStyle,
    fontFamily: FontFamily,
    density: Density,
): TextStyle = TextStyle(
    fontFamily = fontFamily,
    fontSize = style.fontSizeSp.sp,
    lineHeight = with(density) { style.lineHeightPx.toSp() },
    fontWeight = if (style.bold) FontWeight.SemiBold else FontWeight.Normal,
    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
    textAlign = if (style.justify) TextAlign.Justify else TextAlign.Start,
    hyphens = Hyphens.Auto,
    lineBreak = LineBreak.Paragraph,
    textIndent = TextIndent(firstLine = with(density) { style.firstLineIndentPx.toSp() }),
)

/**
 * The text of a block, with any opening treatment already in it.
 *
 * Built here rather than at the point of drawing because a raised initial changes
 * the height of the first line and therefore where it breaks. Measuring the plain
 * string and drawing the decorated one would lay the page out for a paragraph that
 * is not the one on screen — the same failure, from the same cause, as every other
 * time these two descriptions were allowed to differ.
 */
fun readerText(
    text: String,
    style: BlockStyle,
    /**
     * Ranges to paint behind, in this string's own indices — a live selection or a
     * saved highlight, already cut to the slice this page draws.
     *
     * Defaulted empty so [ComposeTextMeasurer] keeps passing none, and it must: the
     * measurer paginates a chapter without knowing which parts of it are marked. That
     * is only safe because a background is the one span style that cannot move a line
     * break — it changes no metric. `MeasureMatchesRenderTest` holds that line, since
     * the day it stops being true is the day pages start losing their last line again.
     */
    marks: List<Pair<IntRange, Color>> = emptyList(),
): AnnotatedString {
    val initialAt =
        if (style.openingInitial && text.isNotEmpty()) text.indexOfFirst { it.isLetterOrDigit() }
        else -1
    if (initialAt < 0 && marks.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        append(text)
        // A letter, not a quotation mark: books raise the first letter of the first
        // word, and a chapter that opens on dialogue should raise the W of "What",
        // not the mark in front of it.
        if (initialAt >= 0) {
            addStyle(
                SpanStyle(fontSize = style.fontSizeSp.sp * ChapterOpening.INITIAL_SCALE),
                start = initialAt,
                end = initialAt + 1,
            )
        }
        marks.forEach { (range, colour) ->
            val from = range.first.coerceIn(0, text.length)
            val to = (range.last + 1).coerceIn(from, text.length)
            if (from < to) addStyle(SpanStyle(background = colour), start = from, end = to)
        }
    }
}
