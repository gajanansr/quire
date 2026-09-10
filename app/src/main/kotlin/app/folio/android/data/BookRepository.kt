package app.folio.android.data

import app.folio.core.model.Book
import app.folio.core.model.Chapter
import app.folio.core.model.ChapterRef
import kotlinx.coroutines.flow.combine
import app.folio.core.model.ReadingPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the Library needs to draw a row, without loading any content. */
/** A bookmark with the title of the book it belongs to. */
data class BookmarkWithBook(
    val bookmark: BookmarkEntity,
    val bookTitle: String,
)

data class LibraryBook(
    val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val progress: Double,
    val chapterCount: Int,
    val lastOpenedAt: Long?,
    val reflowFailed: Boolean,
)

/**
 * The single door between the UI and stored books.
 *
 * Metadata lives in Room and content lives on disk, and keeping both behind one
 * type means callers never have to know which is which — or remember that saving a
 * book means writing a row *and* a directory. The two are always written together
 * and always deleted together.
 */
class BookRepository(
    private val db: FolioDatabase,
    private val store: BookStore,
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun observeLibrary(): Flow<List<LibraryBook>> =
        db.books().observeLibraryWithProgress().map { rows ->
            rows.map { row ->
                LibraryBook(
                    id = row.id,
                    title = row.title,
                    author = row.author,
                    coverPath = row.coverPath,
                    progress = row.progress ?: 0.0,
                    chapterCount = row.chapterCount,
                    lastOpenedAt = row.lastOpenedAt,
                    reflowFailed = row.reflowFailed,
                )
            }
        }

    /** Writes the row and the chapter files as one operation. */
    suspend fun save(book: Book) {
        store.writeChapters(book.id, book.chapters)
        db.books().upsert(
            BookEntity(
                id = book.id,
                title = book.title,
                author = book.author,
                coverPath = book.coverPath,
                sourceFormat = book.sourceFormat.name,
                language = book.metadata.language,
                publisher = book.metadata.publisher,
                identifier = book.metadata.identifier,
                subjects = book.metadata.subjects.joinToString(SUBJECT_SEPARATOR),
                description = book.metadata.description,
                totalChars = book.totalChars,
                chapterCount = book.chapters.size,
                reflowFailed = book.reflowFailed,
                addedAt = db.books().find(book.id)?.addedAt ?: now(),
                lastOpenedAt = db.books().find(book.id)?.lastOpenedAt,
            )
        )
    }

    suspend fun find(id: String): BookEntity? = db.books().find(id)

    suspend fun loadChapter(bookId: String, index: Int): Chapter? =
        store.readChapter(bookId, index)

    /** Chapter titles for the table of contents, without loading any content. */
    suspend fun chapterIndex(bookId: String): List<ChapterRef> = store.readChapterIndex(bookId)

    suspend fun markOpened(bookId: String) {
        db.books().touch(bookId, now())
    }

    suspend fun saveProgress(bookId: String, position: ReadingPosition, progress: Double) {
        db.progress().save(
            ReadingProgressEntity(
                bookId = bookId,
                chapterIndex = position.chapterIndex,
                blockIndex = position.blockIndex,
                charOffset = position.charOffset,
                progress = progress,
                updatedAt = now(),
            )
        )
    }

    // ------------------------------------------------------------- bookmarks

    /**
     * Saves a bookmark at a position, with a snapshot of the text there.
     *
     * The snippet is stored rather than looked up on demand because a bookmark has
     * to survive the book being reprocessed: offsets can shift, but the words the
     * reader marked are what they will recognise in a list.
     */
    suspend fun addBookmark(
        bookId: String,
        position: ReadingPosition,
        snippet: String,
    ): Long = db.bookmarks().add(
        BookmarkEntity(
            bookId = bookId,
            chapterIndex = position.chapterIndex,
            blockIndex = position.blockIndex,
            charOffset = position.charOffset,
            snippet = snippet.take(MAX_SNIPPET).trim(),
            createdAt = now(),
        )
    )

    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>> =
        db.bookmarks().observeFor(bookId)

    /** Every bookmark across the library, newest first, with its book's title. */
    fun observeAllBookmarks(): Flow<List<BookmarkWithBook>> =
        combine(db.bookmarks().observeAll(), db.books().observeLibraryWithProgress()) { marks, books ->
            val titles = books.associate { it.id to it.title }
            marks.mapNotNull { mark ->
                titles[mark.bookId]?.let { BookmarkWithBook(mark, it) }
            }
        }

    suspend fun removeBookmark(id: Long) = db.bookmarks().remove(id)

    /** The stored progress fraction, or null when the book has never been opened. */
    suspend fun storedProgress(bookId: String): Double? =
        db.progress().find(bookId)?.progress

    /** An unread book resumes at the start rather than reporting "no position". */
    suspend fun progressOf(bookId: String): ReadingPosition =
        db.progress().find(bookId)?.let {
            ReadingPosition(it.chapterIndex, it.blockIndex, it.charOffset)
        } ?: ReadingPosition.START

    /**
     * Removes the row, its progress, its bookmarks, and every file.
     *
     * The dependent rows must go explicitly. Left behind, a stale progress row
     * resurrects a deleted book's position if its id is ever reused, and the
     * database grows with every deletion.
     */
    suspend fun delete(bookId: String) {
        db.progress().deleteFor(bookId)
        db.bookmarks().deleteFor(bookId)
        db.books().delete(bookId)
        store.delete(bookId)
    }

    companion object {
        /** Room has no list column; subjects round-trip through this separator. */
        const val SUBJECT_SEPARATOR = "|"

        /** Long enough to recognise a passage, short enough to list. */
        const val MAX_SNIPPET = 240

        fun subjectsOf(stored: String): List<String> =
            stored.split(SUBJECT_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
