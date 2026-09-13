package app.quire.android.importer

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.quire.android.data.BookRepository
import app.quire.android.data.BookStore
import app.quire.android.data.QuireDatabase
import app.quire.android.pdf.AndroidPdfTextSource
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import app.quire.core.fixtures.Fixtures
import app.quire.core.model.FailureReason
import app.quire.core.model.ProcessingStatus
import app.quire.core.model.SourceFormat
import app.quire.core.source.PageRasterizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class BookImporterTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: QuireDatabase
    private lateinit var store: BookStore
    private lateinit var repo: BookRepository

    /** Serves a real file through the UriOpener contract. */
    private class FileOpener(private val file: File, private val size: Long = file.length()) : UriOpener {
        override fun open(uri: Uri): InputStream = file.inputStream()
        override fun sizeOf(uri: Uri): Long = size
        override fun displayName(uri: Uri): String = file.name
    }

    private class FakeRasterizer : PageRasterizer {
        override suspend fun rasterize(pageIndex: Int, dpi: Int) = ByteArray(8)
    }

    private val uri: Uri = Uri.parse("content://test/book")

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

    private fun importer(
        file: File,
        size: Long = file.length(),
        withOcr: Boolean = true,
    ) = BookImporter(
        store = store,
        repository = repo,
        opener = FileOpener(file, size),
        // The production reader, not a JVM stand-in: the importer test should
        // exercise the same PDF path the app ships.
        pdfSource = { AndroidPdfTextSource(it) },
        rasterizer = if (withOcr) ({ FakeRasterizer() }) else null,
        newId = { "fixed-id" },
    )

    // ------------------------------------------------------------ happy paths

    @Test
    fun `imports an epub into the library`() = runBlocking {
        val result = importer(Fixtures.cleanEpub()).import(uri)
        assertTrue("import failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(1, repo.observeLibrary().first().size)
        assertEquals(SourceFormat.EPUB, result.getOrThrow().sourceFormat)
    }

    @Test
    fun `imports a txt into the library`() = runBlocking {
        assertTrue(importer(Fixtures.plainTxt()).import(uri).isSuccess)
        assertEquals(1, repo.observeLibrary().first().size)
    }

    @Test
    fun `imports a text pdf without invoking ocr`() = runBlocking {
        val result = importer(Fixtures.singleColumnPdf()).import(uri)
        assertTrue("import failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(SourceFormat.PDF_TEXT, result.getOrThrow().sourceFormat)
    }

    @Test
    fun `imports a scanned pdf as a book read from its own pages`() = runBlocking {
        val result = importer(Fixtures.imageOnlyPdf()).import(uri)
        assertTrue("import failed: ${result.exceptionOrNull()}", result.isSuccess)

        val book = result.getOrThrow()
        assertEquals(SourceFormat.PDF_SCANNED, book.sourceFormat)
        assertTrue("a scan must route to its original pages", book.reflowFailed)
        assertTrue("a scan invented text: ${book.chapters}", book.chapters.isEmpty())
        // It is still a real book in the Library, with art of its own.
        assertNotNull("a scan should still get a cover", book.coverPath)
    }

    @Test
    fun `the original is copied into app storage`() = runBlocking {
        importer(Fixtures.cleanEpub()).import(uri)
        val original = store.originalOf("fixed-id")
        assertTrue("original not copied", original?.exists() == true)
        assertEquals(Fixtures.cleanEpub().length(), original!!.length())
    }

    @Test
    fun `chapters are readable after import`() = runBlocking {
        importer(Fixtures.cleanEpub()).import(uri)
        assertTrue(repo.loadChapter("fixed-id", 0) != null)
    }

    @Test
    fun `progress states are reported in order`() = runBlocking {
        val seen = mutableListOf<String>()
        importer(Fixtures.cleanEpub()).import(uri) { seen += it::class.simpleName ?: "" }
        assertEquals("Importing", seen.first())
        assertEquals("Ready", seen.last())
        assertTrue("format detection not reported", seen.contains("DetectingFormat"))
    }

    // ----------------------------------------------------------- failure paths

    @Test
    fun `a png named epub is rejected as unsupported`() = runBlocking {
        val result = importer(Fixtures.unsupportedFile()).import(uri)
        assertTrue(result.isFailure)
        assertEquals(
            FailureReason.UNSUPPORTED_FORMAT,
            (result.exceptionOrNull() as ImportFailure).reason,
        )
    }

    @Test
    fun `a malformed epub is rejected as corrupt`() = runBlocking {
        val result = importer(Fixtures.malformedEpub()).import(uri)
        assertEquals(
            FailureReason.CORRUPT_FILE,
            (result.exceptionOrNull() as ImportFailure).reason,
        )
    }

    @Test
    fun `insufficient storage is refused before copying`() = runBlocking {
        val result = importer(Fixtures.cleanEpub(), size = Long.MAX_VALUE / 4).import(uri)
        assertEquals(
            FailureReason.INSUFFICIENT_STORAGE,
            (result.exceptionOrNull() as ImportFailure).reason,
        )
        assertFalse("nothing should have been written", File(temp.root, "books/fixed-id").exists())
    }

    @Test
    fun `an empty file is rejected`() = runBlocking {
        val empty = File(temp.root, "empty.txt").apply { createNewFile() }
        val result = importer(empty).import(uri)
        assertEquals(
            FailureReason.EMPTY_DOCUMENT,
            (result.exceptionOrNull() as ImportFailure).reason,
        )
    }

    @Test
    fun `a failed import leaves no directory and no library row`() = runBlocking {
        importer(Fixtures.malformedEpub()).import(uri)
        assertFalse(
            "a half-imported book was left on disk",
            File(temp.root, "books/fixed-id").exists(),
        )
        assertTrue(
            "a failed import appeared in the library",
            repo.observeLibrary().first().isEmpty(),
        )
    }

    @Test
    fun `a scanned pdf with no ocr available still imports, flagged`() = runBlocking {
        val result = importer(Fixtures.imageOnlyPdf(), withOcr = false).import(uri)
        assertTrue("import failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue(
            "should be flagged for the original-PDF fallback",
            result.getOrThrow().reflowFailed,
        )
    }

    @Test
    fun `importing never throws, whatever the input`() = runBlocking {
        listOf(
            Fixtures.corruptPdf(), Fixtures.unsupportedFile(), Fixtures.malformedEpub(),
            Fixtures.truncatedPdf(), Fixtures.plainTxt(),
        ).forEach { f ->
            val result = runCatching { importer(f).import(uri) }
            assertTrue("import of ${f.name} threw: ${result.exceptionOrNull()}", result.isSuccess)
        }
    }

    @Test
    fun `an epub that ships a cover keeps it`() = runBlocking {
        val book = importer(Fixtures.cleanEpub()).import(uri).getOrThrow()

        val cover = book.coverPath
        assertNotNull("the fixture declares a cover image", cover)
        val file = java.io.File(cover!!)
        assertTrue("cover was not written to disk: $cover", file.exists())
        assertTrue("cover is empty", file.length() > 0)
        // Written verbatim, so what lands on disk is still a decodable image.
        assertEquals(
            listOf(0x89, 0x50, 0x4E, 0x47),
            file.readBytes().take(4).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `a pdf uses its first page as the cover`() = runBlocking {
        val book = importer(Fixtures.singleColumnPdf()).import(uri).getOrThrow()
        val cover = book.coverPath
        assertNotNull("a PDF should render a cover from page one", cover)
        assertTrue("cover was not written", java.io.File(cover!!).length() > 0)
    }

    @Test
    fun `a book without a cover imports anyway`() = runBlocking {
        // The Library draws a gradient for a book with no art. Losing an import over
        // a missing thumbnail would be the wrong trade, so this asserts the book
        // arrives whole and merely lacks a cover.
        val book = importer(Fixtures.plainTxt()).import(uri).getOrThrow()

        assertNull(book.coverPath)
        assertTrue("the book itself should still be readable", book.chapters.isNotEmpty())
    }

    @Test
    fun `a pdf is named by its own metadata, not its filename`() = runBlocking {
        val book = importer(Fixtures.titledPdf()).import(uri).getOrThrow()
        assertEquals("A History of Quiet Things", book.title)
        assertEquals("Ada Marlowe", book.author)
    }

    @Test
    fun `a pdf with a junk title falls back to its title page`() = runBlocking {
        // "Microsoft Word - quiet_things_FINAL_v3.doc" is a real value, not a
        // contrived one. The largest type on page one is the better answer.
        val book = importer(Fixtures.junkTitledPdf()).import(uri).getOrThrow()
        assertEquals("A History of Quiet Things", book.title)
    }
}