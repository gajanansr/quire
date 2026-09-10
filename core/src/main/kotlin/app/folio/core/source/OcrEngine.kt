package app.folio.core.source

/** One recognised line, with its box in the same bottom-left space as [TextRun]. */
data class OcrLine(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
)

data class OcrPage(
    val pageIndex: Int,
    val lines: List<OcrLine>,
    val meanConfidence: Float,
)

/**
 * Recognises text in a rendered page image.
 *
 * `:core` declares this so the pipeline never depends on a particular OCR library.
 * The app implements it with ML Kit on device; tests implement it with a fake, so
 * reflow of OCR output is exercised without an emulator.
 */
interface OcrEngine {
    /** @param image encoded page bitmap (PNG or JPEG bytes). */
    suspend fun recognize(pageIndex: Int, image: ByteArray): OcrPage
}

/** Renders a PDF page to an image for OCR. Implemented on Android with PdfRenderer. */
interface PageRasterizer {
    /** @return encoded image bytes for [pageIndex] at [dpi]. */
    suspend fun rasterize(pageIndex: Int, dpi: Int): ByteArray
}

/**
 * Converts recognised lines into [TextRun]s.
 *
 * This is the join that keeps the pipeline honest: once OCR output wears the same
 * shape as extracted text, reflow, structure detection and normalization are
 * literally the same code for a scan as for a text PDF. Nothing downstream needs
 * to know which it was handed.
 *
 * Font size is inferred from the recognised box height, since OCR reports no font.
 * That is enough for the relative-size comparison heading detection relies on.
 */
fun OcrPage.toTextRuns(): List<TextRun> = lines
    .filter { it.text.isNotBlank() }
    .map { line ->
        TextRun(
            text = line.text,
            x = line.x,
            y = line.y,
            width = line.width,
            height = line.height,
            fontSize = line.height,
            fontName = "OCR",
            bold = false,
            italic = false,
        )
    }
