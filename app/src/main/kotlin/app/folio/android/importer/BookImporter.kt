package app.folio.android.importer

import android.net.Uri
import app.folio.android.data.BookRepository
import app.folio.android.data.BookStore
import app.folio.core.FormatDetector
import app.folio.core.FolioConstants
import app.folio.core.epub.EpubContainer
import app.folio.core.epub.EpubParser
import app.folio.core.model.Book
import app.folio.core.model.FailureReason
import app.folio.core.model.ProcessingStatus
import app.folio.core.model.SourceFormat
import app.folio.core.pdf.PdfPipeline
import app.folio.core.source.PageRasterizer
import app.folio.core.source.PdfTextSource
import app.folio.core.txt.TxtParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A failed import, carrying the reason the designed error state should show. */
class ImportFailure(val reason: FailureReason, cause: Throwable? = null) :
    Exception(reason.name, cause)

/**
 * Turns a picked file into a book in the Library.
 *
 * The order matters and is deliberate: check space, copy, *then* identify. Copying
 * first means the rest of the pipeline works on a stable local file rather than a
 * content URI whose permission can be revoked mid-read. Identifying after copying
 * means detection reads the bytes we actually kept, not what the extension claimed.
 *
 * Any failure removes the book's directory entirely. A half-imported book must never
 * appear in the Library, so there is exactly one cleanup path and every error takes it.
 */
class BookImporter(
    private val store: BookStore,
    private val repository: BookRepository,
    private val opener: UriOpener,
    private val pdfSource: (File) -> PdfTextSource,
    private val rasterizer: ((File) -> PageRasterizer)? = null,
    private val epubParser: EpubParser = EpubParser(),
    private val txtParser: TxtParser = TxtParser(),
    private val pdfPipeline: PdfPipeline = PdfPipeline(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * The book's own cover, saved beside it, or null when it has none.
     *
     * Never fatal. A book whose cover cannot be read is still a book, and the
     * Library already draws a gradient swatch for one without art — losing an
     * import over a thumbnail would be the wrong trade.
     */
    private suspend fun coverFor(id: String, format: SourceFormat, file: File): String? =
        runCatching {
            val bytes = when (format) {
                SourceFormat.EPUB -> EpubContainer(file).use { it.coverImage() }
                SourceFormat.PDF_TEXT, SourceFormat.PDF_OCR, SourceFormat.PDF_SCANNED ->
                    rasterizer?.invoke(file)?.rasterize(0, FolioConstants.COVER_RENDER_DPI)
                // A text file carries no art, and inventing one would be a lie about
                // the source rather than a missing feature.
                SourceFormat.TXT -> null
            }
            bytes?.takeIf { it.isNotEmpty() }?.let { store.writeCover(id, it) }
        }.getOrNull()

    suspend fun import(
        uri: Uri,
        onProgress: (ProcessingStatus) -> Unit = {},
    ): Result<Book> = withContext(Dispatchers.IO) {
        val id = newId()
        try {
            onProgress(ProcessingStatus.Importing)

            val declaredSize = opener.sizeOf(uri)
            if (declaredSize > 0 && !store.hasRoomFor(declaredSize)) {
                throw ImportFailure(FailureReason.INSUFFICIENT_STORAGE)
            }

            val displayName = opener.displayName(uri)
            val copied = runCatching {
                opener.open(uri).use { store.writeOriginal(id, it, extensionOf(displayName)) }
            }.getOrElse { throw ImportFailure(FailureReason.EXTRACTION_FAILED, it) }

            if (copied.length() == 0L) throw ImportFailure(FailureReason.EMPTY_DOCUMENT)

            onProgress(ProcessingStatus.DetectingFormat)
            val format = FormatDetector.detect(copied)
                ?: throw ImportFailure(FailureReason.UNSUPPORTED_FORMAT)

            onProgress(ProcessingStatus.Extracting)
            val title = displayName.substringBeforeLast('.').ifBlank { "Untitled" }
            val book = when (format) {
                SourceFormat.EPUB -> epubParser.parse(copied, id)
                SourceFormat.TXT -> txtParser.parse(copied, id)
                // Detection reads magic bytes, so every PDF arrives as PDF_TEXT;
                // whether it is really a scan is the pipeline's finding, not this
                // one's. The other PDF formats are listed to keep this exhaustive.
                SourceFormat.PDF_TEXT, SourceFormat.PDF_OCR, SourceFormat.PDF_SCANNED ->
                    processPdf(id, title, copied, onProgress)
            }

            val status = book.status
            if (status is ProcessingStatus.Failed) throw ImportFailure(status.reason)

            onProgress(ProcessingStatus.Normalizing)
            // Bound, not inlined into save: the caller gets this Book back, and
            // returning the pre-cover one would hand out a book whose cover exists
            // on disk and in the database but not in the object in hand.
            val saved = book.copy(coverPath = coverFor(id, format, copied))
            repository.save(saved)
            onProgress(ProcessingStatus.Ready)
            Result.success(saved)
        } catch (failure: ImportFailure) {
            store.delete(id)
            Result.failure(failure)
        } catch (unexpected: Exception) {
            // An unsupported or corrupt book must reach the designed error state
            // rather than the crash reporter.
            store.delete(id)
            Result.failure(ImportFailure(FailureReason.EXTRACTION_FAILED, unexpected))
        }
    }

    private suspend fun processPdf(
        id: String,
        title: String,
        file: File,
        onProgress: (ProcessingStatus) -> Unit,
    ): Book = pdfSource(file).use { source ->
        pdfPipeline.process(
            id = id,
            title = title,
            source = source,
            onProgress = onProgress,
        )
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 5 }
            ?: "bin"
}
