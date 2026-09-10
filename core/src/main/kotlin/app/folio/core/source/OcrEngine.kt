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
    /**
     * Pixel size of the rendered image the boxes were measured in.
     *
     * Recognition happens on a raster at [app.folio.core.FolioConstants.OCR_RENDER_DPI],
     * so its coordinates are in pixels, not PDF points. Without the image size the
     * pipeline cannot convert them and every box lands far outside the page.
     */
    val imageWidth: Float,
    val imageHeight: Float,
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
 * Converts recognised lines into [TextRun]s in the page's own coordinate space.
 *
 * This is the join that keeps the pipeline honest: once OCR output wears the same
 * shape as extracted text, reflow, structure detection and normalization are
 * literally the same code for a scan as for a text PDF. Nothing downstream needs
 * to know which it was handed.
 *
 * Scaling is essential, not cosmetic. Boxes arrive in raster pixels; the rest of
 * the pipeline reasons in PDF points against [target]. Left unscaled, a 300 DPI
 * page reports coordinates roughly four times the page height, which puts every
 * line of the book inside the top margin band — where the header detector
 * reasonably concludes it is running furniture and removes it.
 *
 * Font size is inferred from box height, since OCR reports no font. That is enough
 * for the relative-size comparison heading detection relies on.
 */
fun OcrPage.toTextRuns(target: PageGeometry): List<TextRun> {
    val sx = if (imageWidth > 0f) target.width / imageWidth else 1f
    val sy = if (imageHeight > 0f) target.height / imageHeight else 1f

    return lines
        .filter { it.text.isNotBlank() }
        .map { line ->
            val height = line.height * sy
            TextRun(
                text = line.text,
                x = line.x * sx,
                y = line.y * sy,
                width = line.width * sx,
                height = height,
                fontSize = height,
                fontName = "OCR",
                bold = false,
                italic = false,
            )
        }
}
