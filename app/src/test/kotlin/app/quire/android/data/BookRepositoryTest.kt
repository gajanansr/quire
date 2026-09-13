package app.quire.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.quire.core.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BookRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: QuireDatabase
    private lateinit var repo: BookRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), QuireDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = BookRepository(db, BookStore(temp.root)) { clock }
    }

    @After
    fun tearDown() = db.close()

    private fun book(id: String, title: String = "Title") = Book(
        id = id, title = title, author = "Ada Marlowe", coverPath = null,
        metadata = BookMetadata(language = "en"), sourceFormat = SourceFormat.EPUB,
        status = ProcessingStatus.Ready,
        chapters = listOf(
            Chapter(0, "One", listOf(ContentBlock.Paragraph(listOf(InlineSpan("aaaa")))), 0, 4),
            Chapter(1, "Two", listOf(ContentBlock.Paragraph(listOf(InlineSpan("bbbbbb")))), 4, 6),
        ),
    )

    @Test
    fun `a saved book appears in the library`() = runBlocking {
        repo.save(book("a", "The Weight of Silence"))
        val library = repo.observeLibrary().first()
        assertEquals(1, library.size)
        assertEquals("The Weight of Silence", library.single().title)
        assertEquals(2, library.single().chapterCount)
    }

    @Test
    fun `saving writes both the row and the chapter files`() = runBlocking {
        repo.save(book("a"))
        assertEquals("One", repo.loadChapter("a", 0)?.title)
        assertEquals("Two", repo.loadChapter("a", 1)?.title)
    }

    @Test
    fun `an unread book resumes at the start`() = runBlocking {
        repo.save(book("a"))
        assertEquals(ReadingPosition.START, repo.progressOf("a"))
        assertEquals(0.0, repo.observeLibrary().first().single().progress, 1e-9)
    }

    @Test
    fun `saved progress round trips exactly`() = runBlocking {
        repo.save(book("a"))
        repo.saveProgress("a", ReadingPosition(1, 3, 42), 0.6)
        assertEquals(ReadingPosition(1, 3, 42), repo.progressOf("a"))
        assertEquals(0.6, repo.observeLibrary().first().single().progress, 1e-9)
    }

    @Test
    fun `progress survives re-saving the book after reprocessing`() = runBlocking {
        repo.save(book("a"))
        repo.saveProgress("a", ReadingPosition(1, 0, 5), 0.9)
        repo.save(book("a", "Retitled"))
        assertEquals(ReadingPosition(1, 0, 5), repo.progressOf("a"))
    }

    @Test
    fun `re-saving preserves the original addedAt`() = runBlocking {
        clock = 1_000L
        repo.save(book("a"))
        clock = 9_000L
        repo.save(book("a", "Retitled"))
        assertEquals(1_000L, repo.find("a")?.addedAt)
    }

    @Test
    fun `the library orders most recently opened first`() = runBlocking {
        clock = 1_000L; repo.save(book("a", "First"))
        clock = 2_000L; repo.save(book("b", "Second"))
        clock = 3_000L; repo.markOpened("a")

        assertEquals(listOf("First", "Second"), repo.observeLibrary().first().map { it.title })
    }

    @Test
    fun `delete removes the row, the files, and the progress`() = runBlocking {
        repo.save(book("a"))
        repo.saveProgress("a", ReadingPosition(1, 0, 3), 0.5)

        repo.delete("a")

        assertTrue(repo.observeLibrary().first().isEmpty())
        assertNull(repo.loadChapter("a", 0))
        assertFalse(File(temp.root, "books/a").exists())
        // An orphaned progress row would resurrect a deleted book's position if the
        // same id were ever reused, and quietly bloats the database.
        assertNull(db.progress().find("a"))
    }

    @Test
    fun `deleting one book leaves the others intact`() = runBlocking {
        repo.save(book("a", "Keep"))
        repo.save(book("b", "Drop"))
        repo.delete("b")
        assertEquals(listOf("Keep"), repo.observeLibrary().first().map { it.title })
        assertEquals("One", repo.loadChapter("a", 0)?.title)
    }

    @Test
    fun `a reflow-failed book is flagged in the library`() = runBlocking {
        repo.save(book("a").copy(reflowFailed = true))
        assertTrue(repo.observeLibrary().first().single().reflowFailed)
    }
}
