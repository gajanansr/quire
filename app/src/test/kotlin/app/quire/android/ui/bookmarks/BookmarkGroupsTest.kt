package app.quire.android.ui.bookmarks

import app.quire.android.data.BookmarkEntity
import app.quire.android.data.BookmarkWithBook
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Saved passages, gathered under the book they came from.
 *
 * Flat and newest-first interleaved three books by the accident of when each was being
 * read, and the question this screen is actually asked — "what did I keep from that
 * book" — could only be answered by scanning.
 */
class BookmarkGroupsTest {

    private fun mark(
        book: String,
        chapter: Int = 0,
        block: Int = 0,
        char: Int = 0,
        created: Long = 0,
        title: String = "Book $book",
    ) = BookmarkWithBook(
        bookmark = BookmarkEntity(
            bookId = book, chapterIndex = chapter, blockIndex = block,
            charOffset = char, snippet = "c$chapter b$block", createdAt = created,
        ),
        bookTitle = title,
    )

    @Test
    fun `marks are gathered under their book`() {
        val groups = BookmarkGroups.of(
            listOf(mark("a"), mark("b"), mark("a", chapter = 2), mark("b", chapter = 3)),
        )
        assertEquals(2, groups.size)
        assertEquals(setOf("a", "b"), groups.map { it.bookId }.toSet())
        groups.forEach { assertEquals(2, it.marks.size) }
    }

    @Test
    fun `within a book, marks run in reading order`() {
        // Made in the order 9, 2, 5 — a reader jumping about — and shown 2, 5, 9,
        // because someone scanning their own highlights is walking the book.
        val groups = BookmarkGroups.of(
            listOf(
                mark("a", chapter = 9, created = 300),
                mark("a", chapter = 2, created = 100),
                mark("a", chapter = 5, created = 200),
            ),
        )
        assertEquals(listOf(2, 5, 9), groups.single().marks.map { it.bookmark.chapterIndex })
    }

    @Test
    fun `two marks in one chapter run by position, not by when they were made`() {
        val groups = BookmarkGroups.of(
            listOf(
                mark("a", chapter = 1, block = 40, char = 5, created = 100),
                mark("a", chapter = 1, block = 3, char = 900, created = 500),
                mark("a", chapter = 1, block = 3, char = 20, created = 900),
            ),
        )
        assertEquals(
            listOf(3 to 20, 3 to 900, 40 to 5),
            groups.single().marks.map { it.bookmark.blockIndex to it.bookmark.charOffset },
        )
    }

    @Test
    fun `the book marked most recently comes first`() {
        // Not the book most recently *added* — the one being read now. A book started
        // last year and marked this morning belongs at the top.
        val groups = BookmarkGroups.of(
            listOf(
                mark("old", created = 10),
                mark("current", created = 9_000),
                mark("middling", created = 500),
            ),
        )
        assertEquals(listOf("current", "middling", "old"), groups.map { it.bookId })
    }

    @Test
    fun `a book's position comes from its newest mark, not its oldest`() {
        val groups = BookmarkGroups.of(
            listOf(
                mark("a", chapter = 0, created = 1),
                mark("a", chapter = 1, created = 9_999),
                mark("b", created = 500),
            ),
        )
        assertEquals(listOf("a", "b"), groups.map { it.bookId })
    }

    @Test
    fun `the group carries the book's title`() {
        val groups = BookmarkGroups.of(listOf(mark("a", title = "One Indian Girl")))
        assertEquals("One Indian Girl", groups.single().bookTitle)
    }

    @Test
    fun `no bookmarks, no groups`() {
        assertEquals(emptyList<BookmarkGroup>(), BookmarkGroups.of(emptyList()))
    }

    @Test
    fun `every mark survives the grouping`() {
        // The property that matters most: this reorders, it never drops. A reader
        // losing a saved passage to a sort is the one failure this screen cannot have.
        val marks = (1..20).map { mark(book = "b${it % 4}", chapter = it, created = it.toLong()) }
        val grouped = BookmarkGroups.of(marks).flatMap { it.marks }
        assertEquals(marks.size, grouped.size)
        assertEquals(marks.toSet(), grouped.toSet())
    }
}
