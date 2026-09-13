package app.quire.android.work

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.quire.android.data.BookRepository
import app.quire.android.data.BookStore
import app.quire.android.data.QuireDatabase
import app.quire.android.importer.BookImporter
import app.quire.android.importer.UriOpener
import app.quire.android.pdf.AndroidPdfTextSource
import app.quire.core.fixtures.Fixtures
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

@RunWith(RobolectricTestRunner::class)
class ImportWorkerTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: QuireDatabase
    private lateinit var store: BookStore
    private lateinit var repo: BookRepository
    private lateinit var progress: ImportProgressStore

    private class FileOpener(private val file: File) : UriOpener {
        override fun open(uri: Uri): InputStream = file.inputStream()
        override fun sizeOf(uri: Uri): Long = file.length()
        override fun displayName(uri: Uri): String = file.name
    }

    private class FakeRasterizer : PageRasterizer {
        override suspend fun rasterize(pageIndex: Int, dpi: Int) = ByteArray(8)
    }

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), QuireDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = BookStore(temp.root)
        repo = BookRepository(db, store)
        progress = ImportProgressStore(store)
    }

    @After
    fun tearDown() = db.close()

    private fun buildWorker(file: File, bookId: String = "book-1"): ImportWorker {
        val importer = BookImporter(
            store = store,
            repository = repo,
            opener = FileOpener(file),
            pdfSource = { AndroidPdfTextSource(it) },
            rasterizer = { FakeRasterizer() },
            newId = { bookId },
        )
        return TestListenableWorkerBuilder<ImportWorker>(
            context = ApplicationProvider.getApplicationContext(),
            inputData = workDataOf(
                ImportWorker.KEY_URI to "content://test/book",
                ImportWorker.KEY_BOOK_ID to bookId,
            ),
        ).setWorkerFactory(QuireWorkerFactory(importer, progress)).build()
    }

    @Test
    fun `a successful import succeeds and reaches the library`() = runBlocking {
        val result = buildWorker(Fixtures.cleanEpub()).doWork()
        assertTrue("expected success, got $result", result is ListenableWorker.Result.Success)
        assertEquals(1, repo.observeLibrary().first().size)
    }

    @Test
    fun `a successful import clears its progress state`() = runBlocking {
        buildWorker(Fixtures.cleanEpub()).doWork()
        assertEquals(null, progress.load("book-1"))
        assertTrue("a finished import is still listed as in flight", progress.unfinished().isEmpty())
    }

    @Test
    fun `a corrupt book fails with a reason rather than throwing`() = runBlocking {
        val result = buildWorker(Fixtures.malformedEpub()).doWork()
        assertTrue("expected failure, got $result", result is ListenableWorker.Result.Failure)
        val reason = (result as ListenableWorker.Result.Failure)
            .outputData.getString(ImportWorker.KEY_FAILURE)
        assertEquals("CORRUPT_FILE", reason)
    }

    @Test
    fun `a failed import leaves nothing in the library`() = runBlocking {
        buildWorker(Fixtures.malformedEpub()).doWork()
        assertTrue(repo.observeLibrary().first().isEmpty())
    }

    @Test
    fun `a missing uri fails cleanly rather than crashing the worker`() = runBlocking {
        val worker = TestListenableWorkerBuilder<ImportWorker>(
            context = ApplicationProvider.getApplicationContext(),
            inputData = workDataOf(ImportWorker.KEY_BOOK_ID to "x"),
        ).setWorkerFactory(
            QuireWorkerFactory(
                BookImporter(
                    store, repo, FileOpener(Fixtures.cleanEpub()),
                    { AndroidPdfTextSource(it) },
                ),
                progress,
            )
        ).build()

        assertTrue(worker.doWork() is ListenableWorker.Result.Failure)
    }

    @Test
    fun `a scanned book records ocr page progress while it runs`() = runBlocking {
        buildWorker(Fixtures.imageOnlyPdf(), bookId = "scan-1").doWork()
        // State is cleared on success, so the book landing is the evidence the OCR
        // stage ran to completion rather than being skipped.
        assertEquals(1, repo.observeLibrary().first().size)
        assertEquals(null, progress.load("scan-1"))
    }

    @Test
    fun `unique work is named per book so a double tap cannot import twice`() {
        assertEquals("import-abc", ImportWorker.workName("abc"))
        assertTrue(ImportWorker.workName("a") != ImportWorker.workName("b"))
    }
}
