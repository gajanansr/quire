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
