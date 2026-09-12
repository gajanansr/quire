package app.folio.core.model

import kotlinx.serialization.Serializable

@Serializable
/**
 * Where a book came from, and therefore how it is read.
 *
 * [PDF_SCANNED] is the one that changes behaviour: a scan has no reliable text to
 * reflow, so it is read as rendered pages. PDF_OCR remains for books imported
 * before that decision, so their rows still resolve.
 */
enum class SourceFormat { EPUB, PDF_TEXT, PDF_OCR, PDF_SCANNED, TXT }

@Serializable
data class BookMetadata(
    val language: String? = null,
    val publisher: String? = null,
    val identifier: String? = null,
    /** dc:subject values, shown as the genre chips on Book Details. */
    val subjects: List<String> = emptyList(),
    /**
     * The publisher's own description, when the book carries one.
     *
     * Null rather than a generated summary: Book Details shows a Synopsis tab, and
     * an invented synopsis would be worse than an honest absence.
     */
    val description: String? = null,
)

@Serializable
enum class FailureReason {
    CORRUPT_FILE, UNSUPPORTED_FORMAT, ENCRYPTED, EMPTY_DOCUMENT,
    EXTRACTION_FAILED, OCR_FAILED, INSUFFICIENT_STORAGE, INTERRUPTED,
}

@Serializable
sealed interface ProcessingStatus {
    @Serializable data object Idle : ProcessingStatus
    @Serializable data object Importing : ProcessingStatus
    @Serializable data object DetectingFormat : ProcessingStatus
    @Serializable data object Extracting : ProcessingStatus
    @Serializable data class Ocr(val pagesDone: Int, val pagesTotal: Int) : ProcessingStatus
    @Serializable data object DetectingStructure : ProcessingStatus
    @Serializable data object Normalizing : ProcessingStatus
    @Serializable data object Ready : ProcessingStatus
    @Serializable data class Failed(val reason: FailureReason) : ProcessingStatus
}

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val metadata: BookMetadata,
    val sourceFormat: SourceFormat,
    val status: ProcessingStatus,
    val chapters: List<Chapter>,
    /** Set when reflow was not confident; the UI offers the original PDF instead. */
    val reflowFailed: Boolean = false,
) {
    val totalChars: Int get() = chapters.sumOf { it.charCount }
}
