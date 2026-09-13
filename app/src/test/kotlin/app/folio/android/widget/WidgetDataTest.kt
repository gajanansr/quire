package app.folio.android.widget

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.folio.android.data.BookRepository
import app.folio.android.data.BookStore
import app.folio.android.data.FolioDatabase
import app.folio.android.data.HabitRepository
import app.folio.core.habit.DayMinutes
import app.folio.core.model.Book
import app.folio.core.model.BookMetadata
import app.folio.core.model.Chapter
import app.folio.core.model.ContentBlock
import app.folio.core.model.InlineSpan
import app.folio.core.model.ProcessingStatus
import app.folio.core.model.ReadingPosition
import app.folio.core.model.SourceFormat
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
import java.time.ZoneId

/**
 * The one read a widget does, against a real database.
 *
 * [WidgetStateTest] proves the wording is right for a given snapshot; this proves
 * the snapshot is the reader's. The failure this guards against is the quiet one the
 * brief names — a widget showing plausible numbers that belong to nobody.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetDataTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: FolioDatabase
    private lateinit var books: BookRepository
    private lateinit var habits: HabitRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), FolioDatabase::class.java,
        ).allowMainThreadQueries().build()
        books = BookRepository(db, BookStore(temp.root)) { clock }
        habits = HabitRepository(db, zone = { ZoneId.of("UTC") })
    }

    @After
    fun tearDown() = db.close()

    private fun book(id: String, title: String) = Book(
        id = id, title = title, author = "Ada Marlowe", coverPath = null,
        metadata = BookMetadata(language = "en"), sourceFormat = SourceFormat.EPUB,
        status = ProcessingStatus.Ready,
        chapters = listOf(
            Chapter(0, "One", listOf(ContentBlock.Paragraph(listOf(InlineSpan("aaaa")))), 0, 4),
        ),
    )

    private fun load(): WidgetSnapshot = runBlocking { WidgetData.load(books, habits) }

    @Test
    fun `a fresh install has nothing to show, and says so`() = runBlocking {
        val snapshot = load()
        assertFalse(snapshot.hasHistory)
        assertEquals(0, snapshot.libraryCount)
        assertEquals(0, snapshot.totalMinutes)
        assertNull(snapshot.currentBook)
        assertEquals(0, snapshot.habits.currentStreak)
    }

    @Test
    fun `recorded minutes reach the snapshot`() = runBlocking {
        habits.record(listOf(DayMinutes(habits.todayEpochDay(), 24)))
        val snapshot = load()
        assertEquals(24, snapshot.totalMinutes)
        assertEquals(24, snapshot.habits.minutesToday)
        assertTrue(snapshot.hasHistory)
    }

    @Test
    fun `finished books and chapters reach the snapshot`() = runBlocking {
        habits.recordBookFinished()
        habits.recordChapterFinished()
        habits.recordChapterFinished()
        val snapshot = load()
        assertEquals(1, snapshot.booksFinished)
        assertEquals(2, snapshot.chaptersFinished)
    }

    @Test
    fun `the library is counted`() = runBlocking {
        books.save(book("a", "The Weight of Silence"))
        books.save(book("b", "Nightjar"))
        assertEquals(2, load().libraryCount)
    }

    @Test
    fun `the current book is the one most recently opened and started`() = runBlocking {
        books.save(book("a", "The Weight of Silence"))
        books.save(book("b", "Nightjar"))
        clock = 2_000L; books.markOpened("a")
        books.saveProgress("a", ReadingPosition(0, 0, 10), 0.3)
        clock = 3_000L; books.markOpened("b")
        books.saveProgress("b", ReadingPosition(0, 0, 5), 0.1)

        val current = load().currentBook
        assertEquals("b", current?.id)
        assertEquals("Nightjar", current?.title)
        assertEquals(0.1, current?.progress ?: 0.0, 1e-9)
    }

    @Test
    fun `a book that was never opened is not offered as the current one`() = runBlocking {
        // The same rule the Library's Continue Reading card uses. Inviting someone
        // to continue a book they never began is the widget telling a small lie.
        books.save(book("a", "The Weight of Silence"))
        assertNull(load().currentBook)
    }

    @Test
    fun `a finished book is not the one being read`() = runBlocking {
        books.save(book("a", "The Weight of Silence"))
        clock = 2_000L; books.markOpened("a")
        books.saveProgress("a", ReadingPosition(0, 0, 4), 1.0)
        assertNull(load().currentBook)
    }
}
