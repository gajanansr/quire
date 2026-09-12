package app.folio.core.source

/**
 * Renders a PDF page to an image.
 *
 * Survives the removal of the OCR pipeline because two things still need it:
 * a book's cover is its first page, and a scanned book is read as its pages.
 */
interface PageRasterizer {
    /** @return encoded image bytes for [pageIndex] at [dpi]. */
    suspend fun rasterize(pageIndex: Int, dpi: Int): ByteArray
}
