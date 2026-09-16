package app.quire.android.data

import app.quire.core.model.Book
import app.quire.core.model.Chapter
import app.quire.core.model.ChapterRef
import kotlinx.coroutines.flow.combine
import app.quire.core.model.ReadingPosition
import app.quire.android.ui.theme.HighlightColour
import app.quire.android.ui.theme.highlightColourNamed
import app.quire.core.reading.TextAnchor
import app.quire.core.reading.TextSpan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the Library needs to draw a row, without loading any content. */
/**
 * One saved highlight, as the page needs it: which row, which characters, what colour.
 *
 * The colour is the reader's *choice* rather than a `Color` — the page resolves it
 * against whatever theme is in force when it draws, which is what lets a mark made on
 * Paper survive being read on E-ink.
 */
data class SavedHighlight(
    val id: Long,
    val span: TextSpan,
    val colour: HighlightColour,
    /**
     * The reader's own words about this passage, or `""`.
     *
     * Carried with the mark rather than looked up when a mark is tapped: it is what
     * decides whether the options offer "Note" or "Edit note", and it fills the sheet
     * the moment it opens instead of a frame later.
     */
    val note: String = "",
) {
    val hasNote: Boolean get() = note.isNotBlank()
}

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
    private val db: QuireDatabase,
    private val store: BookStore,
    /**
     * Called after any write that changes what a home-screen widget shows.
     *
     * A callback rather than a context this class holds: the repository has no
     * business knowing widgets exist, and a test can count the calls. It sits ahead
     * of [now] deliberately — callers pass the clock as a trailing lambda, and a
     * callback in the last position would silently swallow one and leave every test
     * running on the wall clock.
     */
    private val onDataChanged: () -> Unit = {},
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
        onDataChanged()
    }

    suspend fun find(id: String): BookEntity? = db.books().find(id)

    suspend fun loadChapter(bookId: String, index: Int): Chapter? =
        store.readChapter(bookId, index)

    /** The imported original, for the fallback viewer. */
    fun originalFileOf(bookId: String): java.io.File? = store.originalOf(bookId)

    /** Chapter titles for the table of contents, without loading any content. */
    suspend fun chapterIndex(bookId: String): List<ChapterRef> = store.readChapterIndex(bookId)

    suspend fun markOpened(bookId: String) {
        db.books().touch(bookId, now())
        // Which book is "the one you're reading" is exactly this ordering.
        onDataChanged()
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
        // The Reader persists on dispose, so this is usually once a session — but
        // the scanned-PDF viewer saves its page as it turns, which is why the
        // listener is expected to be cheap and to do its own work off the caller's
        // thread rather than assume it is called rarely.
        onDataChanged()
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
    ): Long = addOnce(
        BookmarkEntity(
            bookId = bookId,
            chapterIndex = position.chapterIndex,
            blockIndex = position.blockIndex,
            charOffset = position.charOffset,
            snippet = snippet.take(MAX_SNIPPET).trim(),
            createdAt = now(),
        )
    )

    /**
     * Saves a mark, unless that exact mark is already saved.
     *
     * The Bookmark control is one tap in the reader's chrome and shows no state, so a
     * reader unsure whether the first tap registered taps again — which is the common
     * case, not the odd one. That used to add a second identical row, and the list
     * then showed the same passage twice with no way to tell the copies apart.
     *
     * Returning the existing id rather than a new one matters: the caller reports
     * "saved" either way, and handing back a fresh id for a row that was not created
     * would be a lie any later lookup would trip over.
     */
    private suspend fun addOnce(mark: BookmarkEntity): Long {
        val already = db.bookmarks().existing(
            bookId = mark.bookId,
            chapterIndex = mark.chapterIndex,
            blockIndex = mark.blockIndex,
            charOffset = mark.charOffset,
            endBlockIndex = mark.endBlockIndex,
            endCharOffset = mark.endCharOffset,
        )
        if (already == null) return db.bookmarks().add(mark)
        // Highlighting a passage that is already highlighted, in another colour, is
        // a *recolour* — one row, not two. Two rows would put the same words in the
        // Bookmarks list twice and stack two washes on one run, where only the last
        // one drawn can be seen: the list and the page would disagree, and the copy
        // underneath would be unreachable.
        if (already.highlightColour != mark.highlightColour) {
            db.bookmarks().recolour(already.id, mark.highlightColour)
        }
        // A blank note never overwrites a real one. Every caller that is not
        // [saveNote] builds its mark with the empty default — tapping Highlight over
        // a passage the reader has already written about is the ordinary case — and
        // carrying that blank through would silently delete the one thing in this
        // database Quire cannot reconstruct. Clearing a note is [setNote]'s job, and
        // it is reached by emptying the field, which is a thing the reader did.
        if (mark.note.isNotBlank() && mark.note != already.note) {
            db.bookmarks().setNote(already.id, mark.note)
        }
        return already.id
    }

    /**
     * Saves a passage the reader chose.
     *
     * The same row as a bookmark, with an end. The snippet is the selected words
     * themselves rather than the top of the page, which is what makes the Bookmarks
     * list read as a commonplace book instead of a list of places.
     */
    suspend fun addHighlight(
        bookId: String,
        chapterIndex: Int,
        span: TextSpan,
        snippet: String,
        colour: HighlightColour,
    ): Long = addOnce(
        BookmarkEntity(
            bookId = bookId,
            chapterIndex = chapterIndex,
            blockIndex = span.start.blockIndex,
            charOffset = span.start.charOffset,
            endBlockIndex = span.end.blockIndex,
            endCharOffset = span.end.charOffset,
            highlightColour = colour.name,
            snippet = snippet.take(MAX_SNIPPET).trim(),
            createdAt = now(),
        )
    )

    /** Repaints one mark the reader already made. */
    suspend fun recolourHighlight(id: Long, colour: HighlightColour) =
        db.bookmarks().recolour(id, colour.name)

    // ----------------------------------------------------------------- notes

    /**
     * Attaches the reader's own words to a passage, marking it if it is not marked.
     *
     * A note needs an anchor, and in Quire the anchor is the mark: it is what the
     * Bookmarks list shows, what a tap on the page resolves to, and what carries the
     * note back to the exact characters it was written about. So writing a note on a
     * bare selection also highlights it — deliberately, because a note the reader
     * cannot see on the page is a thought they will never find again.
     *
     * Routed through [addOnce], so noting a passage that is already marked edits that
     * mark rather than stacking a second one over the same words.
     *
     * The [setNote] afterwards is not redundant. [addOnce] refuses to carry a blank
     * note over a real one — that guard is what protects a noted passage from a later
     * tap on Highlight — so *clearing* a note has to be said explicitly, and this is
     * where the reader emptying the field is turned into an empty column.
     */
    suspend fun saveNote(
        bookId: String,
        chapterIndex: Int,
        span: TextSpan,
        snippet: String,
        colour: HighlightColour,
        note: String,
    ): Long {
        val written = note.trim()
        val id = addOnce(
            BookmarkEntity(
                bookId = bookId,
                chapterIndex = chapterIndex,
                blockIndex = span.start.blockIndex,
                charOffset = span.start.charOffset,
                endBlockIndex = span.end.blockIndex,
                endCharOffset = span.end.charOffset,
                highlightColour = colour.name,
                note = written,
                snippet = snippet.take(MAX_SNIPPET).trim(),
                createdAt = now(),
            )
        )
        db.bookmarks().setNote(id, written)
        return id
    }

    /**
     * Rewrites the note on a mark that already exists.
     *
     * By id, because this is the route from the Bookmarks list and from a mark the
     * reader tapped on the page — both of which already know which row they mean, and
     * neither of which should have to rebuild a span to say so.
     */
    suspend fun setNote(id: Long, note: String) = db.bookmarks().setNote(id, note.trim())

    /**
     * What the reader has already written about exactly this passage, or `""`.
     *
     * All six coordinates, the way [BookmarkDao.existing] matches: a note belongs to
     * the characters it was written about, and a mark that merely starts in the same
     * place is a different mark.
     */
    suspend fun noteFor(bookId: String, chapterIndex: Int, span: TextSpan): String =
        db.bookmarks().existing(
            bookId = bookId,
            chapterIndex = chapterIndex,
            blockIndex = span.start.blockIndex,
            charOffset = span.start.charOffset,
            endBlockIndex = span.end.blockIndex,
            endCharOffset = span.end.charOffset,
        )?.note.orEmpty()

    /**
     * The highlights in one chapter, ready to paint and ready to tap.
     *
     * The id travels with the span because a tap on the page has to resolve to a
     * *row*: it is what tells "change this one's colour" from "change one that looks
     * like it". The colour is resolved from its stored name here, at the one boundary
     * where the string leaves the database, so nothing downstream ever handles a name
     * it might not recognise.
     */
    fun observeHighlights(bookId: String, chapterIndex: Int): Flow<List<SavedHighlight>> =
        db.bookmarks().observeFor(bookId).map { marks ->
            marks.filter { it.chapterIndex == chapterIndex && it.isHighlight }
                .map {
                    SavedHighlight(
                        id = it.id,
                        span = TextSpan(
                            TextAnchor(it.blockIndex, it.charOffset),
                            TextAnchor(it.endBlockIndex, it.endCharOffset),
                        ),
                        colour = highlightColourNamed(it.highlightColour),
                        note = it.note,
                    )
                }
        }

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

    /**
     * When the reader last turned a page, or null if they never have.
     *
     * Across every book, because the question it answers is about the reader rather
     * than about one title: is a book open in front of them right now?
     */
    suspend fun lastPageTurnAt(): Long? = db.progress().lastUpdatedAt()

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
        onDataChanged()
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
