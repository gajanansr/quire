package app.folio.android.importer

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.folio.android.data.BookRepository
import app.folio.android.data.BookStore
import app.folio.android.data.FolioDatabase
import app.folio.android.pdf.AndroidPdfTextSource
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import app.folio.core.fixtures.FakeOcrEngine
import app.folio.core.fixtures.Fixtures
import app.folio.core.model.FailureReason
import app.folio.core.model.ProcessingStatus
import app.folio.core.model.SourceFormat
import app.folio.core.source.PageRasterizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private lateinit var db: FolioDatabase
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
            ApplicationProvider.getApplicationContext(), FolioDatabase::class.java,
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
        ocr = if (withOcr) FakeOcrEngine({ p ->
            listOf(
                "Recognised body text on page $p running the full measure of a line",
                "and continuing onto a second line to form a paragraph.",
            )
        }) else null,
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
    fun `imports a scanned pdf through ocr`() = runBlocking {
        val result = importer(Fixtures.imageOnlyPdf()).import(uri)
        assertTrue("import failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(SourceFormat.PDF_OCR, result.getOrThrow().sourceFormat)
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
}
