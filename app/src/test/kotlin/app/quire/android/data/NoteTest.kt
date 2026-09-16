package app.quire.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.quire.android.ui.theme.HighlightColour
import app.quire.core.reading.TextAnchor
import app.quire.core.reading.TextSpan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A note is the reader's own words, and losing them is the only failure that matters.
 *
 * Everything else in this table Quire can rebuild: a colour is a choice it can ask
 * for again, a span is arithmetic, a snippet is copied from the book. A note is the
 * one thing in Quire that exists nowhere else — nobody can retype a thought they had
 * three chapters ago. So these tests are almost all about *survival*: what happens to
 * a note when the mark under it is recoloured, re-marked, or marked a second time by
 * a reader who forgot they already had.
 */
@RunWith(RobolectricTestRunner::class)
class NoteTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: QuireDatabase
    private lateinit var books: BookRepository

    /** One passage, used throughout: chapter 3, the same sixty-six characters. */
    private val span = TextSpan(TextAnchor(7, 120), TextAnchor(7, 186))

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

    private suspend fun note(
        text: String,
        colour: HighlightColour = HighlightColour.DEFAULT,
    ): Long = books.saveNote(
        bookId = "b1",
        chapterIndex = 3,
        span = span,
        snippet = "The words they kept.",
        colour = colour,
        note = text,
    )

    @Test
    fun `a note written against a bare selection marks the passage too`() {
        // A note needs somewhere to live, and the mark is that place: it is what the
        // Bookmarks list shows, what a tap on the page resolves to, and what takes
        // the note back to the exact characters it was written about. A note with no
        // mark would be a thought the reader could never find again.
        runBlocking {
            note("Mistrust this. Check the date.")

            val marks = saved()
            assertEquals("writing a note did not save the passage", 1, marks.size)
            assertTrue("the noted passage is not a highlight", marks.first().isHighlight)
            assertEquals("Mistrust this. Check the date.", marks.first().note)
        }
    }

    @Test
    fun `a note survives recolouring the mark it belongs to`() {
        // The one the brief names outright. Recolouring is an UPDATE of a single
        // column and has no business touching another, but "has no business" is not
        // an assertion — and a note quietly emptied by a swatch tap is unrecoverable.
        runBlocking {
            val id = note("Mistrust this.")
            books.recolourHighlight(id, HighlightColour.DOUBT)

            assertEquals("recolouring emptied the note", "Mistrust this.", saved().first().note)
            assertEquals("DOUBT", saved().first().highlightColour)
        }
    }

    @Test
    fun `a note survives marking the same passage again`() {
        // The dedupe path: highlighting words that are already highlighted is a
        // recolour, not a second row. It goes through the same code as a first
        // highlight and carries an empty note with it, so the row it finds must keep
        // the note it already has rather than take the incoming blank.
        runBlocking {
            note("The reader's own words.")
            books.addHighlight(
                bookId = "b1", chapterIndex = 3, span = span,
                snippet = "The words they kept.", colour = HighlightColour.FACT,
            )

            assertEquals("a second highlight made a second row", 1, saved().size)
            assertEquals(
                "highlighting over a noted passage threw the note away",
                "The reader's own words.",
                saved().first().note,
            )
            assertEquals("FACT", saved().first().highlightColour)
        }
    }

    @Test
    fun `editing a note replaces it rather than adding a second mark`() {
        runBlocking {
            val first = note("A first thought.")
            val second = note("A better one.")

            assertEquals("editing a note made a new row", first, second)
            assertEquals(1, saved().size)
            assertEquals("A better one.", saved().first().note)
        }
    }

    @Test
    fun `clearing a note leaves the mark behind`() {
        // Deleting the highlight because its note went empty would take the reader's
        // colour choice with it. Emptying the note is a smaller act than unmarking a
        // passage, and it has to stay smaller.
        runBlocking {
            val id = note("Something I no longer think.", colour = HighlightColour.DOUBT)
            books.setNote(id, "")

            assertEquals("clearing a note removed the mark", 1, saved().size)
            assertEquals("", saved().first().note)
            assertEquals("DOUBT", saved().first().highlightColour)
        }
    }

    @Test
    fun `a note is stored the way it was written, less the stray whitespace`() {
        // A note typed into a field arrives with whatever the keyboard left on it.
        // Trimmed at the edges only: the line breaks inside are the reader's.
        runBlocking {
            note("  One thought.\n\nAnd another.\n ")
            assertEquals("One thought.\n\nAnd another.", saved().first().note)
        }
    }

    @Test
    fun `a passage nobody wrote about has no note rather than a null one`() {
        // Empty string, never null. A nullable column whose null means "the other
        // kind of row" is a condition every later reader of this table has to
        // remember — the same argument that gave plain bookmarks a colour.
        runBlocking {
            books.addHighlight(
                bookId = "b1", chapterIndex = 3, span = span,
                snippet = "The words they kept.", colour = HighlightColour.DEFAULT,
            )
            assertEquals("", saved().first().note)
        }
    }

    @Test
    fun `the note travels with the highlight the page paints`() {
        // The Reader needs to know a mark has a note without a second query: it is
        // what decides whether tapping the mark offers "Note" or "Edit note", and
        // what fills the sheet when it opens.
        runBlocking {
            note("Check this against the 1971 edition.")
            val painted = books.observeHighlights("b1", 3).first()

            assertEquals(1, painted.size)
            assertEquals("Check this against the 1971 edition.", painted.first().note)
            assertTrue("a mark with words on it does not say so", painted.first().hasNote)
        }
    }

    @Test
    fun `looking up a passage that was never noted answers empty, not an exception`() {
        runBlocking {
            assertEquals("", books.noteFor("b1", 3, span))
        }
    }

    @Test
    fun `a note belongs to its own passage and not to its neighbour`() {
        runBlocking {
            note("About these words.")
            books.saveNote(
                bookId = "b1", chapterIndex = 3,
                span = TextSpan(TextAnchor(9, 0), TextAnchor(9, 40)),
                snippet = "Different words.",
                colour = HighlightColour.DEFAULT,
                note = "About those words.",
            )

            assertEquals(2, saved().size)
            assertEquals("About these words.", books.noteFor("b1", 3, span))
            assertEquals(
                "About those words.",
                books.noteFor("b1", 3, TextSpan(TextAnchor(9, 0), TextAnchor(9, 40))),
            )
        }
    }
}
