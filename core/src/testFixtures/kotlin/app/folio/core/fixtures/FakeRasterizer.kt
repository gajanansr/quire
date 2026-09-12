package app.folio.core.fixtures

import app.folio.core.source.PageRasterizer

/**
 * A rasterizer that records what it was asked for.
 *
 * Outlived the OCR pipeline it was written for: covers are rendered from a book's
 * first page, and a scanned book is read as its pages, so both still need this.
 */
class FakeRasterizer : PageRasterizer {
    var rasterizedPages = mutableListOf<Int>(); private set

    override suspend fun rasterize(pageIndex: Int, dpi: Int): ByteArray {
        rasterizedPages += pageIndex
        return ByteArray(16) { pageIndex.toByte() }
    }
}
