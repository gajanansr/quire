package app.folio.android.ui.details

import app.folio.android.data.BookEntity
import app.folio.android.data.BookRepository
import app.folio.core.model.Chapter
import app.folio.core.reading.ReadingEstimates
import kotlin.math.roundToInt

enum class DetailsTab(val label: String) {
    SYNOPSIS("Synopsis"),
    DETAILS("Details"),
    AUTHOR("Author"),
}

/**
 * Everything Book Details shows, derived from persisted data.
 *
 * Kept as a plain value with no Android or Compose types so the derivation — which
 * is where the mistakes live — is testable on the JVM.
 */
data class BookDetailsState(
    val loading: Boolean = true,
    val id: String = "",
    val title: String = "",
    val author: String? = null,
    val coverPath: String? = null,
    val subjects: List<String> = emptyList(),
    val description: String? = null,
    val progress: Double = 0.0,
    val totalChars: Int = 0,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String? = null,
    val reflowFailed: Boolean = false,
    val dailyGoalMinutes: Int = 20,
    val format: String = "",
) {
    val percentComplete: Int get() = (progress.coerceIn(0.0, 1.0) * 100).roundToInt()

    /** "Ch. 4" — the handoff's abbreviation, 1-based for display. */
    val chapterLabel: String
        get() = if (chapterCount == 0) "—" else "Ch. ${chapterIndex + 1}"

    val pagesLabel: String
        get() = if (totalChars == 0) "—"
        else "${ReadingEstimates.currentPage(totalChars, progress)}/" +
            "${ReadingEstimates.pageCount(totalChars)}"

    val timeLeftLabel: String
        get() = ReadingEstimates.timeLeftLabel(totalChars, progress) ?: "Done"

    val paceSentence: String?
        get() = ReadingEstimates.paceSentence(totalChars, progress, dailyGoalMinutes)

    /** The primary action: a book that failed reflow offers the original instead. */
    val started: Boolean get() = progress > 0.0

    /**
     * The Synopsis tab's body.
     *
     * Null when the book carries no description. Book Details would rather show an
     * honest absence than a synthesised summary of a book nobody has described.
     */
    val synopsis: String? get() = description?.takeIf { it.isNotBlank() }

    companion object {
        fun from(
            entity: BookEntity,
            progress: Double,
            chapterIndex: Int,
            chapterTitle: String?,
            dailyGoalMinutes: Int,
        ) = BookDetailsState(
            loading = false,
            id = entity.id,
            title = entity.title,
            author = entity.author,
            coverPath = entity.coverPath,
            subjects = BookRepository.subjectsOf(entity.subjects),
            description = entity.description,
            progress = progress,
            totalChars = entity.totalChars,
            chapterIndex = chapterIndex,
            chapterCount = entity.chapterCount,
            chapterTitle = chapterTitle,
            reflowFailed = entity.reflowFailed,
            dailyGoalMinutes = dailyGoalMinutes,
            format = formatLabel(entity.sourceFormat),
        )

        /**
         * The source format in the reader's terms.
         *
         * "PDF_OCR" is machinery. That a book needed recognising is Folio's
         * business, so both PDF paths read simply as "PDF".
         */
        fun formatLabel(sourceFormat: String): String = when (sourceFormat) {
            "EPUB" -> "EPUB"
            "TXT" -> "Text"
            "PDF_TEXT", "PDF_OCR" -> "PDF"
            else -> "Book"
        }
    }
}
