package app.quire.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.quire.core.model.ReadingPosition
import app.quire.core.reading.TextAnchor
import app.quire.core.reading.TextSpan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Marking the same place twice leaves one bookmark, not two.
 *
 * Reported from a real phone: "same book marks are saved multiple times". The
 * Bookmark control is a single tap in the reader's chrome with no visible state, so
 * tapping it again — because you are not sure the first one registered, which is the
 * common case — silently added a second identical row. The list then shows the same
 * passage twice and there is no way to tell the copies apart.
 *
 * A repository guard rather than a unique index, deliberately: the fix needs no
 * schema change, and a UNIQUE constraint would make the second tap an exception to
 * catch rather than a no-op to ignore.
 */
@RunWith(RobolectricTestRunner::class)
class BookmarkDedupeTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: QuireDatabase
    private lateinit var books: BookRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), QuireDatabase::class.java,
        ).allowMainThreadQueries().build()
        books = BookRepository(db, BookStore(temp.root))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun saved() = books.observeBookmarks("b1").first()

    @Test
    fun `bookmarking the same place twice saves it once`() = runBlocking {
        val where = ReadingPosition(chapterIndex = 3, blockIndex = 7, charOffset = 120)
        books.addBookmark("b1", where, "A passage worth returning to.")
        books.addBookmark("b1", where, "A passage worth returning to.")

        assertEquals("the same place was saved twice", 1, saved().size)
    }

    @Test
    fun `the second tap returns the bookmark that already existed`() = runBlocking {
        // So a caller can show "saved" rather than reporting a new id that is really
        // the old one under a different number.
        val where = ReadingPosition(chapterIndex = 3, blockIndex = 7, charOffset = 120)
        val first = books.addBookmark("b1", where, "A passage.")
        val second = books.addBookmark("b1", where, "A passage.")

        assertEquals(first, second)
    }

    @Test
    fun `a different place in the same book is its own bookmark`() = runBlocking {
        books.addBookmark("b1", ReadingPosition(3, 7, 120), "One passage.")
        books.addBookmark("b1", ReadingPosition(3, 7, 900), "Another passage.")
        books.addBookmark("b1", ReadingPosition(4, 0, 0), "A third.")

        assertEquals(3, saved().size)
    }

    @Test
    fun `the same offsets in a different book do not collide`() = runBlocking {
        books.addBookmark("b1", ReadingPosition(3, 7, 120), "One book.")
        books.addBookmark("b2", ReadingPosition(3, 7, 120), "Another book.")

        assertEquals(1, saved().size)
        assertEquals(1, books.observeBookmarks("b2").first().size)
    }

    @Test
    fun `highlighting the same passage twice keeps one`() = runBlocking {
        val span = TextSpan.of(TextAnchor(2, 10), TextAnchor(2, 64))
        books.addHighlight("b1", chapterIndex = 1, span = span, snippet = "The words.")
        books.addHighlight("b1", chapterIndex = 1, span = span, snippet = "The words.")

        assertEquals("the same passage was highlighted twice", 1, saved().size)
    }

    @Test
    fun `a highlight that merely starts where a bookmark sits is kept`() = runBlocking {
        // A bookmark is a highlight of no width, so the two share a table. They are
        // still different things: one marks a place, the other keeps words, and
        // collapsing them would silently discard the reader's chosen passage.
        val at = ReadingPosition(chapterIndex = 1, blockIndex = 2, charOffset = 10)
        books.addBookmark("b1", at, "A saved place")
        books.addHighlight(
            "b1", chapterIndex = 1,
            span = TextSpan.of(TextAnchor(2, 10), TextAnchor(2, 64)),
            snippet = "The words.",
        )

        assertEquals(2, saved().size)
        assertNotEquals(saved()[0].isHighlight, saved()[1].isHighlight)
    }
}
