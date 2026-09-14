package app.quire.android.ui.bookmarks

import app.quire.android.data.BookmarkWithBook

/**
 * The saved passages of one book, in the order they appear in it.
 */
data class BookmarkGroup(
    val bookId: String,
    val bookTitle: String,
    val marks: List<BookmarkWithBook>,
)

/**
 * Gathers saved passages under the book they came from.
 *
 * Flat and newest-first is the wrong shape for this list. Bookmarks are made while
 * reading, so a reader with three books gets them interleaved by the accident of when
 * they happened to be reading each — and the question being asked of this screen is
 * almost always "what did I keep from *that* book", which a flat list answers only by
 * scanning.
 *
 * Two different orders, deliberately:
 *
 * - **Books** are ordered by their most recent mark, so the book you are reading now
 *   is at the top and does not move to the bottom because you started it last year.
 * - **Marks within a book** are ordered by where they sit *in the book*, not by when
 *   they were made. A reader scanning their own highlights is walking the book, and a
 *   passage from chapter 2 belongs above one from chapter 9 however the reading went.
 */
object BookmarkGroups {

    fun of(bookmarks: List<BookmarkWithBook>): List<BookmarkGroup> =
        bookmarks
            .groupBy { it.bookmark.bookId }
            .map { (bookId, marks) ->
                BookmarkGroup(
                    bookId = bookId,
                    // Every row carries the title; they agree, because they come from
                    // the same join.
                    bookTitle = marks.first().bookTitle,
                    marks = marks.sortedWith(
                        compareBy(
                            { it.bookmark.chapterIndex },
                            { it.bookmark.blockIndex },
                            { it.bookmark.charOffset },
                        ),
                    ),
                )
            }
            .sortedByDescending { group -> group.marks.maxOf { it.bookmark.createdAt } }
}
