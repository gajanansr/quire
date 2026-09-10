package app.folio.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Library metadata. Chapter content lives on disk, one file per chapter, so a book
 * row stays small and the reader can load a chapter without touching the rest.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val sourceFormat: String,
    val language: String?,
    val publisher: String?,
    val identifier: String?,
    /** dc:subject values joined with "|" — Room stores no lists natively. */
    val subjects: String,
    val description: String?,
    val totalChars: Int,
    val chapterCount: Int,
    /** Reflow was not confident; the UI offers the original file instead. */
    val reflowFailed: Boolean,
    val addedAt: Long,
    val lastOpenedAt: Long?,
)

/** One row per book: where the reader resumes. */
@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int,
    val blockIndex: Int,
    val charOffset: Int,
    val progress: Double,
    val updatedAt: Long,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val blockIndex: Int,
    val charOffset: Int,
    /** Snapshot of the text, so a bookmark survives reprocessing. */
    val snippet: String,
    val createdAt: Long,
)

/**
 * One day's reading total, keyed by local epoch-day.
 *
 * Stored as a rollup rather than derived from sessions on every read: the Library
 * card and the streak heatmap want it on every launch, and re-summing a year of
 * sessions each time would be wasteful.
 */
@Entity(tableName = "reading_days")
data class ReadingDayEntity(
    @PrimaryKey val epochDay: Long,
    val minutes: Int,
    /** The goal in force that day, so history is not rewritten by changing it. */
    val goalMinutes: Int,
)

/** One row, id 0. Small enough that a table beats a preferences file. */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 0,
    val dailyGoalMinutes: Int = 10,
    val themeName: String = "LIGHT",
    val readerFont: String = "SERIF",
    val readerFontSizeSp: Float = 19f,
    val readerJustify: Boolean = false,
    val booksFinished: Int = 0,
    val chaptersFinished: Int = 0,
    val onboarded: Boolean = false,
)
