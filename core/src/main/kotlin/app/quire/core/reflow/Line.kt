package app.quire.core.reflow

import app.quire.core.source.TextRun

/**
 * A visual line of text: the runs that share a baseline, merged and ordered
 * left to right.
 *
 * Coordinates keep the PDF's bottom-left origin, so a larger [y] is higher on the
 * page and lines sort top-to-bottom by descending [y].
 */
data class Line(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val medianFontSize: Float,
    val bold: Boolean,
    val runs: List<TextRun>,
) {
    /** Right edge, used to spot lines that stop short of the margin. */
    val right: Float get() = x + width

    val isBlank: Boolean get() = text.isBlank()
}
