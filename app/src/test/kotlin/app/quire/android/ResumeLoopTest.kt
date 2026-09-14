package app.quire.android

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.quire.android.data.BookRepository
import app.quire.android.data.BookStore
import app.quire.android.data.QuireDatabase
import app.quire.android.importer.BookImporter
import app.quire.android.importer.UriOpener
import app.quire.android.pdf.AndroidPdfTextSource
import app.quire.android.ui.reader.ReaderPreferences
import app.quire.android.ui.reader.ReaderState
import app.quire.android.ui.reader.ReaderTransitions
import app.quire.android.ui.reader.ReaderWindow
import app.quire.core.fixtures.Fixtures
import app.quire.core.model.ReadingPosition
import app.quire.core.paginate.BlockStyle
import app.quire.core.paginate.Measured
import app.quire.core.paginate.Paginator
import app.quire.core.paginate.TextMeasurer
import app.quire.core.paginate.Viewport
import app.quire.core.reading.TextAnchor
import app.quire.core.source.PageRasterizer
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

    private lateinit var db: QuireDatabase
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
            ApplicationProvider.getApplicationContext(), QuireDatabase::class.java,
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

    /**
     * Opens a book at its saved position, exactly as ReaderHost does.
     *
     * Through `ReaderWindow`, because that is now what ReaderHost does: a chapter is
     * laid out a window at a time, and the resume path is the one that has to place a
     * reader inside a window that does not start at the chapter's first character.
     */
    private suspend fun open(
        bookId: String,
        preferences: ReaderPreferences = ReaderPreferences(),
    ): ReaderState {
        val entity = repo.find(bookId)!!
        val saved = repo.progressOf(bookId)
        val chapter = repo.loadChapter(bookId, saved.chapterIndex)!!
        val settings = preferences.toSettings(1f)
        val window = ReaderWindow.openAt(
            chapter, saved, ReaderWindow.charsBehind(viewport, settings),
        ) { from, maxPages ->
            paginator.paginateWindow(chapter, from, maxPages, viewport, settings)
        }
        return ReaderTransitions.openedChapter(
            ReaderState(
                bookId = bookId, bookTitle = entity.title,
                chapterCount = entity.chapterCount, bookTotalChars = entity.totalChars,
                preferences = preferences,
            ),
            chapter, window, saved,
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

    // -------------------------------------------- the hard version, with a window

    /**
     * The book this whole feature exists for: one chapter, hundreds of thousands of
     * characters, no outline. Its own chapter 0, read deep enough that the window
     * cannot start at the chapter's first character.
     */
    private suspend fun savedDeepInOneChapter(id: String): ReadingPosition {
        import(Fixtures.largeBook(), id)
        val chapter = repo.loadChapter(id, 0)!!
        assertTrue(
            "the fixture is too short to need a window: ${chapter.textLength} characters",
            chapter.textLength > 100_000,
        )
        val deep = chapter.cursorAt(chapter.textLength / 2)
        val at = ReadingPosition(0, deep.blockIndex, deep.charOffset)
        repo.saveProgress(id, at, 0.5)
        return at
    }

    @Test
    fun `a book resumed deep in one long chapter opens on the saved sentence`() = runBlocking {
        val at = savedDeepInOneChapter("deep")
        val opened = open("deep")

        assertTrue(
            "the window started at the chapter's first character, so nothing was saved",
            opened.windowStart != TextAnchor(0, 0),
        )
        // Not `position`, which is the top of the page: the saved place is somewhere
        // inside the page the reader is put on, and that is what "resume on the same
        // sentence" means when the sentence is not the first one on its page.
        val chapter = opened.chapter!!
        val page = opened.currentPage!!
        val from = page.slices.first().let { chapter.offsetOf(it.blockIndex, it.startChar) }
        val to = page.slices.last().let { chapter.offsetOf(it.blockIndex, it.endChar) }
        val saved = chapter.offsetOf(at.blockIndex, at.charOffset)
        assertTrue("$saved is not on the page [$from, $to)", saved in from until to)
    }

    @Test
    fun `the loop closes for a book that is one long chapter`() = runBlocking {
        savedDeepInOneChapter("loop")

        val first = open("loop")
        val left = readAndClose(first, pageTurns = 4)
        assertTrue("the reader never left the window's first page", left != first.position)

        val second = open("loop")
        assertEquals("a windowed chapter did not resume where it was left", left, second.position)
    }

    @Test
    fun `opening and closing a windowed book does not walk it backwards`() = runBlocking {
        // The regression this caught. Where a window starts decides where its pages
        // break, and the Reader saves the top of the page it was on — so a start taken
        // as "exactly so far before the reader" made every reopen land mid-page and
        // save a place a little earlier than the last one. A reader who opened a book
        // and closed it without reading would have been walked back through it a
        // fraction of a page at a time. `ReaderWindow.anchorOffset` snaps the start to
        // a grid so the tiling is the same and the saved page top is still a page top.
        savedDeepInOneChapter("stable")

        val first = readAndClose(open("stable"), pageTurns = 2)
        val second = readAndClose(open("stable"), pageTurns = 0)
        val third = readAndClose(open("stable"), pageTurns = 0)

        assertEquals("closing and reopening moved the reader", first, second)
        assertEquals("the place did not settle", second, third)
    }

    @Test
    fun `a windowed chapter survives a type-size change between sessions`() = runBlocking {
        // `ResumeLoopTest`'s hard case with the window in the way. Nothing about a
        // chunk boundary may reach the saved place: the position is a character
        // offset, the window is worked out from it afresh, and the reader comes back
        // to the same sentence at a size that has a different number of pages in it.
        savedDeepInOneChapter("size")

        val session = open("size", ReaderPreferences(fontSizeSp = 19f))
        val left = readAndClose(session, pageTurns = 3)

        val reopened = open("size", ReaderPreferences(fontSizeSp = 24f))
        val chapter = reopened.chapter!!
        assertEquals("chapter changed", left.chapterIndex, reopened.position.chapterIndex)
        assertTrue("landed outside the window", reopened.pageIndex in reopened.pages.indices)

        val was = chapter.offsetOf(left.blockIndex, left.charOffset)
        val now = chapter.offsetOf(reopened.position.blockIndex, reopened.position.charOffset)
        assertTrue("reopened past where the reader left off: $was -> $now", now <= was)
        assertTrue(
            "reopened a page or more before where the reader left off: $was -> $now",
            was - now < 4_000,
        )
    }

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
