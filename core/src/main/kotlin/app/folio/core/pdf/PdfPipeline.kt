package app.folio.core.pdf

import app.folio.core.FolioConstants
import app.folio.core.model.Book
import app.folio.core.metadata.TitleResolver
import app.folio.core.ocr.JunkFilter
import app.folio.core.model.BookMetadata
import app.folio.core.model.FailureReason
import app.folio.core.model.ProcessingStatus
import app.folio.core.model.SourceFormat
import app.folio.core.normalize.Normalizer
import app.folio.core.reflow.ReflowPipeline
import app.folio.core.source.OcrEngine
import app.folio.core.source.OutlineEntry
import app.folio.core.source.PageGeometry
import app.folio.core.source.PageRasterizer
import app.folio.core.source.PdfPage
import app.folio.core.source.PdfTextSource
import app.folio.core.source.toTextRuns
import app.folio.core.structure.ChapterDetector

/**
 * Turns a PDF into a normalized [Book]: classify, optionally recognise, reflow,
 * detect structure, assemble.
 *
 * OCR is applied per page and only where a text layer is missing, then folded back
 * into the page stream so reflow sees one uniform document. A book that is half
 * scanned costs one OCR pass over its scanned half, not over all of it.
 *
 * Nothing here throws for a bad document. Every failure resolves to a [Book] with
 * a [ProcessingStatus.Failed] status and a reason the UI can present, because a
 * corrupt file must reach the designed error state rather than the crash reporter.
 */
class PdfPipeline(
    private val scanned: ScannedDetector = ScannedDetector(),
    private val reflow: ReflowPipeline = ReflowPipeline(),
    private val chapters: ChapterDetector = ChapterDetector(),
    private val normalizer: Normalizer = Normalizer(),
) {

    /**
     * @param ocr null when no OCR is available; a scanned book then imports flagged
     *   for the original-PDF fallback rather than failing.
     */
    suspend fun process(
        id: String,
        title: String,
        source: PdfTextSource,
        ocr: OcrEngine? = null,
        rasterizer: PageRasterizer? = null,
        onProgress: (ProcessingStatus) -> Unit = {},
    ): Book {
        if (source.isEncrypted()) return failed(id, title, FailureReason.ENCRYPTED)
        if (source.pageCount() == 0) return failed(id, title, FailureReason.EMPTY_DOCUMENT)

        onProgress(ProcessingStatus.Extracting)
        val verdict = runCatching { scanned.classify(source) }
            .getOrElse { return failed(id, title, FailureReason.EXTRACTION_FAILED) }

        var usedOcr = false
        var ocrDegraded = false
        var effective: PdfTextSource = source

        if (verdict.isScanned) {
            if (ocr == null || rasterizer == null) {
                // No recogniser available: keep the book, offer the original PDF.
                return normalizer.assemble(
                    id = id, title = title, author = null, metadata = BookMetadata(),
                    sourceFormat = SourceFormat.PDF_TEXT, chapters = emptyList(),
                    reflowFailed = true,
                )
            }
            val recognised = recognise(source, verdict, ocr, rasterizer, onProgress)
            usedOcr = recognised.pages.isNotEmpty()
            ocrDegraded = recognised.meanConfidence < FolioConstants.MIN_OCR_CONFIDENCE
            effective = OcrBackedSource(source, recognised.pages)
        }

        onProgress(ProcessingStatus.Normalizing)
        val result = runCatching { reflow.reflow(effective) }
            .getOrElse { return failed(id, title, FailureReason.EXTRACTION_FAILED) }

        if (result.blocks.isEmpty()) return failed(id, title, FailureReason.EMPTY_DOCUMENT)

        onProgress(ProcessingStatus.DetectingStructure)
        val outline = runCatching { source.outline() }.getOrDefault(emptyList())
        val detected = chapters.detect(result.blocks, outline, result.pageBreaks)

        val poor = ocrDegraded || result.confidence < FolioConstants.MIN_REFLOW_CONFIDENCE

        // The filename is the floor, not the answer. Reading the document's own
        // metadata and its title page can do better, and the resolver refuses both
        // when they look like machinery.
        val named = TitleResolver.resolve(
            filename = title,
            info = runCatching { source.documentInfo() }.getOrNull(),
            pageOne = runCatching { effective.page(0).runs }.getOrDefault(emptyList()),
        )

        return normalizer.assemble(
            id = id,
            title = named.title,
            author = named.author,
            metadata = BookMetadata(),
            sourceFormat = if (usedOcr) SourceFormat.PDF_OCR else SourceFormat.PDF_TEXT,
            chapters = detected,
            reflowFailed = poor,
        )
    }

    private data class Recognised(val pages: Map<Int, PdfPage>, val meanConfidence: Float)

    /**
     * Recognises the pages that need it, one at a time to bound memory.
     *
     * A page that fails to render or recognise is skipped rather than aborting the
     * import: the rest of the book is still worth reading, and the shortfall shows
     * up as reduced confidence.
     */
    private suspend fun recognise(
        source: PdfTextSource,
        verdict: ScanVerdict,
        ocr: OcrEngine,
        rasterizer: PageRasterizer,
        onProgress: (ProcessingStatus) -> Unit,
    ): Recognised {
        val out = mutableMapOf<Int, PdfPage>()
        val confidences = mutableListOf<Float>()
        val total = verdict.pagesNeedingOcr.size

        verdict.pagesNeedingOcr.forEachIndexed { done, index ->
            onProgress(ProcessingStatus.Ocr(done, total))
            val geometry = runCatching { source.page(index).geometry }
                .getOrDefault(PageGeometry(index, 612f, 792f))

            runCatching {
                val image = rasterizer.rasterize(index, FolioConstants.OCR_RENDER_DPI)
                // Cleaned before anything downstream sees it, so reflow, structure
                // detection and the reader all work on the same text and none of
                // them has to know a page was ever photographed.
                JunkFilter.clean(ocr.recognize(index, image))
            }.onSuccess { page ->
                if (page.lines.isEmpty()) return@onSuccess
                out[index] = PdfPage(geometry, page.toTextRuns(geometry))
                confidences += page.meanConfidence
            }
        }
        onProgress(ProcessingStatus.Ocr(total, total))

        val mean = if (confidences.isEmpty()) 0f else confidences.average().toFloat()
        return Recognised(out, mean)
    }

    /** Serves recognised pages where they exist and the original page otherwise. */
    private class OcrBackedSource(
        private val delegate: PdfTextSource,
        private val recognised: Map<Int, PdfPage>,
    ) : PdfTextSource {
        override fun pageCount() = delegate.pageCount()
        override fun page(index: Int): PdfPage = recognised[index] ?: delegate.page(index)
        override fun outline(): List<OutlineEntry> = delegate.outline()
        override fun isEncrypted() = delegate.isEncrypted()
        override fun close() = delegate.close()
    }

    private fun failed(id: String, title: String, reason: FailureReason) = Book(
        id = id, title = title, author = null, coverPath = null,
        metadata = BookMetadata(), sourceFormat = SourceFormat.PDF_TEXT,
        status = ProcessingStatus.Failed(reason), chapters = emptyList(),
    )
}
