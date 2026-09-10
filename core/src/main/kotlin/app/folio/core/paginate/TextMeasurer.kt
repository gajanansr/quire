package app.folio.core.paginate

/** How a block is set: enough for the measurer to lay it out. */
data class BlockStyle(
    val fontSizeSp: Float,
    val lineHeightPx: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/**
 * The result of laying out one block at a given width.
 *
 * [lineEnds] holds the character offset just past the end of each line, which is
 * what lets the paginator split a paragraph without ever cutting inside a line.
 */
data class Measured(
    val heightPx: Float,
    val lineEnds: List<Int>,
) {
    val lineCount: Int get() = lineEnds.size
}

/**
 * Lays out text.
 *
 * `:core` declares this so the pagination algorithm can be tested with a
 * deterministic fake, exactly as `PdfTextSource` and `OcrEngine` are. `:app`
 * implements it with Compose's own `TextMeasurer`.
 *
 * Pagination is the one part of the Reader where an off-by-one silently loses a
 * sentence, so it is worth being able to test at unit speed against known metrics
 * rather than only by reading pages on a device.
 */
interface TextMeasurer {
    fun measure(text: String, style: BlockStyle, widthPx: Float): Measured
}

data class Viewport(val widthPx: Float, val heightPx: Float)

data class TypographySettings(
    val fontSizeSp: Float = 19f,
    val lineHeightMultiple: Float = 1.55f,
    val fontKey: String = "serif",
    val justify: Boolean = false,
    /**
     * Device pixels per scale-independent pixel.
     *
     * Load-bearing, and easy to omit. The viewport arrives in device pixels while
     * type is specified in sp; without this factor the paginator believes lines are
     * (on a 2.75x screen) nearly three times shorter than they render, and packs
     * far more onto a page than fits. The symptom is a clipped last line, which
     * reads as text going missing rather than as a unit mismatch.
     */
    val pixelsPerSp: Float = 1f,
) {
    val bodyLineHeightPx: Float get() = fontSizeSp * lineHeightMultiple * pixelsPerSp
}
