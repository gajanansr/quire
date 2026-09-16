package app.quire.android.ui.bookmarks

import app.quire.android.data.BookmarkEntity
import app.quire.android.data.BookmarkWithBook
import app.quire.android.ui.note.NoteEdit
import app.quire.android.ui.note.NoteOutcome
import app.quire.core.reading.TextAnchor
import app.quire.core.reading.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notes in the Bookmarks list: shown where the passage is shown, opened where it is.
 *
 * The list is the only place a note can be found once the reader has left the page it
 * was written on, which makes the route from a row to its words load-bearing. It goes
 * through the same [app.quire.android.ui.note.NoteDraft] the Reader uses, so both
 * places save by the same rules — two paths into one sheet rather than two sheets.
 */
class BookmarkNoteTest {

    private fun mark(
        book: String = "a",
        chapter: Int = 0,
        block: Int = 0,
        char: Int = 0,
        endBlock: Int = block,
        endChar: Int = char,
        note: String = "",
        created: Long = 0,
        id: Long = 1,
    ) = BookmarkWithBook(
        bookmark = BookmarkEntity(
            id = id, bookId = book, chapterIndex = chapter, blockIndex = block,
            charOffset = char, endBlockIndex = endBlock, endCharOffset = endChar,
            note = note, snippet = "c$chapter b$block", createdAt = created,
        ),
        bookTitle = "Book $book",
    )

    @Test
    fun `a note comes through the grouping untouched`() {
        // BookmarkGroups already decides the shape of this screen — books ordered by
        // their most recent mark, marks ordered by where they sit in the book — and a
        // note must not change any of that. It is something a row *shows*, not
        // something it is sorted by.
        val marks = listOf(
            mark(book = "a", chapter = 9, note = "About chapter nine.", created = 5, id = 1),
            mark(book = "a", chapter = 2, created = 9, id = 2),
            mark(book = "b", chapter = 0, note = "Another book.", created = 1, id = 3),
        )
        val groups = BookmarkGroups.of(marks)

        assertEquals(listOf("a", "b"), groups.map { it.bookId })
        assertEquals(
            "a note reordered the marks inside a book",
            listOf(2, 9),
            groups.first().marks.map { it.bookmark.chapterIndex },
        )
        assertEquals(
            "About chapter nine.",
            groups.first().marks.last().bookmark.note,
        )
    }

    @Test
    fun `a row says whether there is a note on it`() {
        assertFalse(mark().bookmark.hasNote)
        assertTrue(mark(note = "Something.").bookmark.hasNote)
        // Whitespace is not something. A note of three spaces would otherwise draw an
        // empty block under the passage and put an Edit where there is nothing to
        // edit.
        assertFalse("blank space counts as a note", mark(note = "   ").bookmark.hasNote)
    }

    @Test
    fun `opening a row's note opens it on what is stored`() {
        // Reading a note must not count as editing it: the sheet opens on `saved`,
        // and Save stays dark until something actually changes.
        val draft = noteDraftOf(mark(chapter = 3, block = 7, char = 120, endChar = 186, note = "A thought.", id = 41))

        assertEquals("A thought.", draft.text)
        assertEquals("A thought.", draft.saved)
        assertEquals(41L, draft.bookmarkId)
        assertEquals(NoteOutcome.NOTHING, NoteEdit.outcome(draft))
        assertFalse(NoteEdit.canSave(draft))
    }

    @Test
    fun `the draft carries the characters the mark covers`() {
        // Saving goes back through the same span the mark holds, which is what keeps
        // the note on the row it came from instead of creating a second mark beside
        // it. Six numbers, all of them.
        val draft = noteDraftOf(mark(chapter = 3, block = 7, char = 120, endBlock = 8, endChar = 12))

        assertEquals(TextSpan(TextAnchor(7, 120), TextAnchor(8, 12)), draft.span)
    }

    @Test
    fun `the passage shown above the field is the one the list shows`() {
        // The Bookmarks route has no page on screen to look at, so the stored snippet
        // is the only thing that can say what the note is about.
        val entry = mark(note = "A thought.")
        assertEquals(entry.bookmark.snippet, noteDraftOf(entry).snippet)
    }

    @Test
    fun `a plain bookmark can be written about too`() {
        // A bookmark is a highlight of no width. A reader who marked a place and
        // wants to say why should not have to re-find the words and highlight them.
        val draft = noteDraftOf(mark(chapter = 4, block = 0, char = 0, id = 7))

        assertEquals(7L, draft.bookmarkId)
        assertEquals(NoteOutcome.WRITE, NoteEdit.outcome(draft.copy(text = "Why I stopped here.")))
    }
}
