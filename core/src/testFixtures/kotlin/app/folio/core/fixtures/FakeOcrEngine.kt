package app.folio.core.fixtures

import app.folio.core.source.OcrEngine
import app.folio.core.source.OcrLine
import app.folio.core.source.OcrPage
import app.folio.core.source.PageRasterizer

/**
 * Returns scripted recognition results so OCR-driven reflow is testable with no
 * device. Confidence and failure are both controllable, because the pipeline's
 * behaviour on poor and failed OCR matters as much as its behaviour on good OCR.
 */
class FakeOcrEngine(
    private val linesPerPage: (Int) -> List<String>,
    private val confidence: Float = 0.9f,
    private val failOnPage: Int? = null,
) : OcrEngine {

    var recognizedPages = mutableListOf<Int>(); private set

    override suspend fun recognize(pageIndex: Int, image: ByteArray): OcrPage {
        if (pageIndex == failOnPage) error("OCR failed on page $pageIndex")
        recognizedPages += pageIndex

        var y = 700f
        val lines = linesPerPage(pageIndex).map { text ->
            OcrLine(
                text = text, x = 72f, y = y, width = text.length * 5.5f, height = 11f,
                confidence = confidence,
            ).also { y -= 16f }
        }
        return OcrPage(pageIndex, lines, confidence, imageWidth = 612f, imageHeight = 792f)
    }
}

/** Produces deterministic bytes; the fake engine ignores their content. */
class FakeRasterizer : PageRasterizer {
    var rasterizedPages = mutableListOf<Int>(); private set
    override suspend fun rasterize(pageIndex: Int, dpi: Int): ByteArray {
        rasterizedPages += pageIndex
        return ByteArray(16) { pageIndex.toByte() }
    }
}
