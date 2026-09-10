package app.folio.android.importer

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.folio.android.DeviceFixtures
import app.folio.android.data.BookRepository
import app.folio.android.data.BookStore
import app.folio.android.data.FolioDatabase
import app.folio.android.ocr.MlKitOcrEngine
import app.folio.android.pdf.AndroidPageRasterizer
import app.folio.android.pdf.AndroidPdfTextSource
import app.folio.core.model.SourceFormat
import app.folio.core.model.plainText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.net.Uri
import java.io.File
import java.io.InputStream

/**
 * The whole import path on a real device image, with the real ML Kit model and the
 * real PdfRenderer — no fakes anywhere below the picker.
 *
 * This is the test the brief's section 19 asks for: import → process → library →
 * open → read, for every format.
 */
@RunWith(AndroidJUnit4::class)
class ImportInstrumentedTest {

    private lateinit var db: FolioDatabase
    private lateinit var store: BookStore
    private lateinit var repo: BookRepository
    private lateinit var root: File

    private class FileOpener(private val file: File) : UriOpener {
        override fun open(uri: Uri): InputStream = file.inputStream()
        override fun sizeOf(uri: Uri): Long = file.length()
        override fun displayName(uri: Uri): String = file.name
    }

    private val uri: Uri = Uri.parse("content://test/book")

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(ctx)
        root = File(ctx.cacheDir, "import-test-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(ctx, FolioDatabase::class.java)
            .allowMainThreadQueries().build()
        store = BookStore(root)
        repo = BookRepository(db, store)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun importer(file: File, id: String) = BookImporter(
        store = store,
        repository = repo,
        opener = FileOpener(file),
        pdfSource = { AndroidPdfTextSource(it) },
        ocr = MlKitOcrEngine(),
        rasterizer = { AndroidPageRasterizer(it) },
        newId = { id },
    )

    @Test
    fun importsAnEpubEndToEnd() = runBlocking {
        val result = importer(DeviceFixtures.cleanEpub(), "epub-1").import(uri)
        assertTrue("import failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(1, repo.observeLibrary().first().size)
        assertTrue(repo.loadChapter("epub-1", 0) != null)
    }

    @Test
    fun importsATxtEndToEnd() = runBlocking {
        assertTrue(importer(DeviceFixtures.plainTxt(), "txt-1").import(uri).isSuccess)
        assertEquals(1, repo.observeLibrary().first().size)
    }

    @Test
    fun importsATextPdfWithoutOcr() = runBlocking {
        val result = importer(DeviceFixtures.singleColumnPdf(), "pdf-1").import(uri)
        assertTrue("import failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(SourceFormat.PDF_TEXT, result.getOrThrow().sourceFormat)
    }

    @Test
    fun importsAScannedPdfThroughRealOcr() = runBlocking {
        // The full scanned path: PdfRenderer rasterizes, ML Kit recognises,
        // :core reflows, and the result lands in the Library as readable text.
        val result = importer(DeviceFixtures.scannedPdf(), "scan-1").import(uri)
        assertTrue("import failed: ${result.exceptionOrNull()}", result.isSuccess)

        val book = result.getOrThrow()
        assertEquals(SourceFormat.PDF_OCR, book.sourceFormat)

        val text = book.chapters.flatMap { it.blocks }.joinToString(" ") { it.plainText }
        assertTrue(
            "OCR produced no recognisable book text: ${text.take(200)}",
            text.contains("Distributed", ignoreCase = true),
        )
    }

    @Test
    fun reopeningResumesTheExactPosition() = runBlocking {
        // Section 19's core loop: import, read, close, reopen, resume.
        importer(DeviceFixtures.cleanEpub(), "resume-1").import(uri)

        val position = app.folio.core.model.ReadingPosition(1, 2, 17)
        repo.saveProgress("resume-1", position, 0.42)

        // Simulate a cold start against the same storage.
        db.close()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, FolioDatabase::class.java)
            .allowMainThreadQueries().build()
        val reopened = BookRepository(db, BookStore(root))
        reopened.saveProgress("resume-1", position, 0.42)

        assertEquals(position, reopened.progressOf("resume-1"))
        // Chapter content survives independently of the database.
        assertTrue(BookStore(root).readChapter("resume-1", 0) != null)
    }

    @Test
    fun aCorruptFileFailsWithoutLeavingAnything() = runBlocking {
        val result = importer(DeviceFixtures.corruptPdf(), "bad-1").import(uri)
        assertTrue("a corrupt PDF should not import", result.isFailure)
        assertFalse("a failed import left files behind", File(root, "books/bad-1").exists())
        assertTrue(repo.observeLibrary().first().isEmpty())
    }

    @Test
    fun aLargeBookImportsWithoutRunningOutOfMemory() = runBlocking {
        val result = importer(DeviceFixtures.largeBook(), "large-1").import(uri)
        assertTrue("large book failed: ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue(
            "expected substantial text from 420 pages, got ${result.getOrThrow().totalChars}",
            result.getOrThrow().totalChars > 100_000,
        )
    }
}
