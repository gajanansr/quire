package app.folio.android

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.folio.android.data.BookRepository
import app.folio.android.data.BookStore
import app.folio.android.data.FolioDatabase
import app.folio.android.importer.BookImporter
import app.folio.android.importer.UriOpener
import app.folio.android.pdf.AndroidPdfTextSource
import app.folio.android.ui.reader.ReaderPreferences
import app.folio.android.ui.reader.ReaderState
import app.folio.android.ui.reader.ReaderTransitions
import app.folio.core.fixtures.Fixtures
import app.folio.core.model.ReadingPosition
import app.folio.core.paginate.BlockStyle
import app.folio.core.paginate.Measured
import app.folio.core.paginate.Paginator
import app.folio.core.paginate.TextMeasurer
import app.folio.core.paginate.Viewport
import app.folio.core.source.PageRasterizer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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
import java.io.File
import java.io.InputStream

/**
 * The loop the brief's section 19 asks for, for every format:
 *
 *     import → process → library → open → read → close → reopen → resume
 *
 * Run against the real repository, the real on-disk store, and the real paginator,
 * with only text *measurement* faked — the one part that genuinely needs a device.
 */
@RunWith(RobolectricTestRunner::class)
class ResumeLoopTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: FolioDatabase
    private lateinit var store: BookStore
    private lateinit var repo: BookRepository

    private class FileOpener(private val file: File) : UriOpener {
        override fun open(uri: Uri): InputStream = file.inputStream()
        override fun sizeOf(uri: Uri): Long = file.length()
        override fun displayName(uri: Uri): String = file.name
    }

    private class FakeRasterizer : PageRasterizer {
        override suspend fun rasterize(pageIndex: Int, dpi: Int) = ByteArray(8)
    }

    private class FixedMeasurer : TextMeasurer {
        override fun measure(text: String, style: BlockStyle, widthPx: Float): Measured {
            val perLine = (40 * (19f / style.fontSizeSp)).toInt().coerceAtLeast(1)
            val ends = mutableListOf<Int>()
            var c = 0
            while (c < text.length) { c = (c + perLine).coerceAtMost(text.length); ends += c }
            if (ends.isEmpty()) ends += 0
            return Measured(ends.size * style.lineHeightPx, ends)
        }
    }

    private val uri: Uri = Uri.parse("content://test/book")
    private val viewport = Viewport(1000f, 19f * 1.55f * 20)
    private val paginator = Paginator(FixedMeasurer())

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), FolioDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = BookStore(temp.root)
        repo = BookRepository(db, store)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun import(file: File, id: String) {
        val importer = BookImporter(
            store = store,
            repository = repo,
            opener = FileOpener(file),
            pdfSource = { AndroidPdfTextSource(it) },
            rasterizer = { FakeRasterizer() },
            newId = { id },
        )
        val result = importer.import(uri)
        assertTrue("${file.name} failed to import: ${result.exceptionOrNull()}", result.isSuccess)
    }

    /** Opens a book at its saved position, exactly as ReaderHost does. */
    private suspend fun open(
        bookId: String,
        preferences: ReaderPreferences = ReaderPreferences(),
    ): ReaderState {
        val entity = repo.find(bookId)!!
        val saved = repo.progressOf(bookId)
        val chapter = repo.loadChapter(bookId, saved.chapterIndex)!!
        val pages = paginator.paginate(chapter, viewport, preferences.toSettings(1f))
        return ReaderTransitions.openedChapter(
            ReaderState(
                bookId = bookId, bookTitle = entity.title,
                chapterCount = entity.chapterCount, bookTotalChars = entity.totalChars,
                preferences = preferences,
            ),
            chapter, pages, saved,
        )
    }

    private suspend fun readAndClose(state: ReaderState, pageTurns: Int): ReadingPosition {
        var s = state
        repeat(pageTurns) { ReaderTransitions.nextPage(s)?.let { s = it } }
        repo.saveProgress(s.bookId, s.position, s.progress)
        return s.position
    }

    // -------------------------------------------------------------- the loop

    private fun runLoop(file: File, id: String, turns: Int = 2) = runBlocking {
        import(file, id)

        // Library
        assertTrue(repo.find(id) != null)

        // Open → read → close
        val first = open(id)
        assertTrue("${file.name} produced no pages", first.pages.isNotEmpty())
        val left = readAndClose(first, turns)

        // Reopen
        val second = open(id)
        assertEquals("${file.name} did not resume where it was left", left, second.position)
    }

    @Test
    fun `epub resumes exactly where it was left`() = runLoop(Fixtures.cleanEpub(), "epub")

    @Test
    fun `txt resumes exactly where it was left`() = runLoop(Fixtures.plainTxt(), "txt")

    @Test
    fun `a text pdf resumes exactly where it was left`() =
        runLoop(Fixtures.singleColumnPdf(), "pdf")

    @Test
    fun `a large book resumes exactly where it was left`() =
        runLoop(Fixtures.largeBook(), "large", turns = 5)

    // ------------------------------------------------------- the hard version

    @Test
    fun `the position survives a typography change between sessions`() = runBlocking {
        // The reason positions are character offsets. Someone reads at 19sp, closes
        // the book, changes the type size, and reopens: the page number they were on
        // no longer exists, but the sentence does.
        import(Fixtures.cleanEpub(), "b")

        val session = open("b", ReaderPreferences(fontSizeSp = 19f))
        val left = readAndClose(session, pageTurns = 1)

        val reopened = open("b", ReaderPreferences(fontSizeSp = 24f))

        assertEquals("chapter changed", left.chapterIndex, reopened.position.chapterIndex)

        // Compared as a distance into the chapter, not by charOffset alone. An
        // offset is measured from its own block, so two offsets in different blocks
        // are not comparable — (block 4, char 0) is far past (block 2, char 372),
        // and comparing the numbers says the opposite.
        val chapter = reopened.chapter!!
        fun into(position: ReadingPosition): Int =
            chapter.blockTexts.take(position.blockIndex).sumOf { it.length } + position.charOffset

        assertTrue(
            "reopened past where the reader left off: " +
                "${into(left)} -> ${into(reopened.position)} characters in",
            into(reopened.position) <= into(left),
        )
        assertTrue("landed outside the book", reopened.pageIndex in reopened.pages.indices)
    }

    @Test
    fun `an unread book opens at the beginning`() = runBlocking {
        import(Fixtures.cleanEpub(), "fresh")
        assertEquals(ReadingPosition.START, open("fresh").position)
    }

    @Test
    fun `progress persists alongside the position`() = runBlocking {
        import(Fixtures.largeBook(), "p")
        val state = open("p")
        readAndClose(state, pageTurns = 3)

        val stored = repo.storedProgress("p")
        assertTrue("no progress was stored", stored != null)
        assertTrue("progress should have advanced past zero, was $stored", stored!! > 0.0)
    }

    @Test
    fun `a bookmark survives being saved and read back`() = runBlocking {
        import(Fixtures.cleanEpub(), "bm")
        val state = open("bm")
        val position = ReaderTransitions.nextPage(state)!!.position

        repo.addBookmark("bm", position, "A passage worth returning to.")

        val marks = repo.observeBookmarks("bm").first()
        assertEquals(1, marks.size)
        assertEquals(position.chapterIndex, marks.single().chapterIndex)
        assertEquals(position.charOffset, marks.single().charOffset)
        assertEquals("A passage worth returning to.", marks.single().snippet)
    }
}
