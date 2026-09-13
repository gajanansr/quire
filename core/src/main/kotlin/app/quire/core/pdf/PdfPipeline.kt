package app.quire.core.pdf

import app.quire.core.QuireConstants
import app.quire.core.model.Book
import app.quire.core.metadata.TitleResolver
import app.quire.core.model.BookMetadata
import app.quire.core.model.FailureReason
import app.quire.core.model.ProcessingStatus
import app.quire.core.model.SourceFormat
import app.quire.core.normalize.Normalizer
import app.quire.core.reflow.ReflowPipeline
import app.quire.core.source.OutlineEntry
import app.quire.core.source.PageGeometry
import app.quire.core.source.PdfPage
import app.quire.core.source.PdfTextSource
import app.quire.core.structure.ChapterDetector

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
     * Turns a PDF into a book, or into the honest statement that it is a scan.
     *
     * A PDF with a text layer is reflowed as before. A scan imports successfully and
     * is marked for reading as original pages — it is a real book in the Library,
     * with a cover and a title, that happens to be read rather than reflowed.
     */
    suspend fun process(
        id: String,
        title: String,
        source: PdfTextSource,
        onProgress: (ProcessingStatus) -> Unit = {},
    ): Book {
        if (source.isEncrypted()) return failed(id, title, FailureReason.ENCRYPTED)
        if (source.pageCount() == 0) return failed(id, title, FailureReason.EMPTY_DOCUMENT)

        onProgress(ProcessingStatus.Extracting)
        val verdict = runCatching { scanned.classify(source) }
            .getOrElse { return failed(id, title, FailureReason.EXTRACTION_FAILED) }

        // A scan is shown as the pages it actually is.
        //
        // Recognising them and reflowing the result was tried and abandoned. The
        // problem is not that recognition is bad but that its mistakes are
        // invisible: a filter that is right nineteen times in twenty still drops a
        // page of someone's book, silently, and they find out later or never. Whole
        // pages, rendered as printed, are always correct. "This one cannot be
        // reflowed" is an honest limit; a book with sentences quietly missing is a
        // broken product.
        //
        // The reader is told, and gets the original. See ORIGINAL_PAGES copy.
        if (verdict.isScanned) {
            return normalizer.assemble(
                id = id, title = title, author = null, metadata = BookMetadata(),
                sourceFormat = SourceFormat.PDF_SCANNED, chapters = emptyList(),
                reflowFailed = true,
            )
        }
        val effective: PdfTextSource = source

        onProgress(ProcessingStatus.Normalizing)
        val result = runCatching { reflow.reflow(effective) }
            .getOrElse { return failed(id, title, FailureReason.EXTRACTION_FAILED) }

        if (result.blocks.isEmpty()) return failed(id, title, FailureReason.EMPTY_DOCUMENT)

        onProgress(ProcessingStatus.DetectingStructure)
        val detected = chapters.detect(result.blocks, declaredBy(source), result.pageBreaks)

        val poor = result.confidence < QuireConstants.MIN_REFLOW_CONFIDENCE

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
            sourceFormat = SourceFormat.PDF_TEXT,
            chapters = detected,
            reflowFailed = poor,
        )
    }

    /**
     * The best structure the document states about itself, or nothing.
     *
     * Three declarations, strongest first. A tagged structure tree says outright
     * which runs are headings and in what order. Bookmarks are the producer's own
     * navigation. A hyperlinked contents page points at destinations someone chose.
     * None of them is inferred, which is the only property that matters here — the
     * alternative is not a weaker source but a guess, and a wrong chapter boundary
     * is permanent, silent, and corrupts both navigation and progress.
     *
     * When a document declares nothing, this returns nothing and the book is one
     * chapter. That is the correct answer, not a degraded one.
     */
    private fun declaredBy(source: PdfTextSource): List<OutlineEntry> {
        fun read(of: () -> List<OutlineEntry>) =
            runCatching(of).getOrDefault(emptyList()).takeIf { it.isNotEmpty() }

        return read { source.declaredHeadings() }
            ?: read { source.outline() }
            ?: read { source.contentsLinks() }
            ?: emptyList()
    }

    private fun failed(id: String, title: String, reason: FailureReason) = Book(
        id = id, title = title, author = null, coverPath = null,
        metadata = BookMetadata(), sourceFormat = SourceFormat.PDF_TEXT,
        status = ProcessingStatus.Failed(reason), chapters = emptyList(),
    )
}
