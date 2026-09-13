package app.quire.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.quire.android.importer.BookImporter
import app.quire.android.importer.UriOpener
import app.quire.android.pdf.AndroidPageRasterizer
import app.quire.android.pdf.AndroidPdfTextSource
import app.quire.core.model.ReadingPosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream

/**
 * Imports into the **real** application graph — the on-disk Room database and
 * `filesDir` the shipped app uses, not an in-memory stand-in.
 *
 * Two purposes. It proves `QuireGraph` wires up correctly outside a test harness,
 * and it leaves real books in the app's library so the populated Library screen can
 * be seen rather than imagined.
 */
@RunWith(AndroidJUnit4::class)
class RealGraphImportTest {

    private class FileOpener(private val file: File, private val name: String) : UriOpener {
        override fun open(uri: Uri): InputStream = file.inputStream()
        override fun sizeOf(uri: Uri): Long = file.length()
        override fun displayName(uri: Uri): String = name
    }

    private val uri: Uri = Uri.parse("content://seed/book")

    @Test
    fun importsRealBooksIntoTheShippedGraph() = runBlocking {
        val quireApp = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as QuireApp
        val graph = quireApp.graph

        val books = listOf(
            DeviceFixtures.cleanEpub() to "A History of Quiet Things.epub",
            DeviceFixtures.chapteredPdf() to "The Weight of Silence.pdf",
            DeviceFixtures.plainTxt() to "Notes on Distributed Systems.txt",
            DeviceFixtures.scannedPdf() to "A Scanned Volume.pdf",
        )

        books.forEachIndexed { index, (file, name) ->
            val importer = BookImporter(
                store = graph.store,
                repository = graph.repository,
                opener = FileOpener(file, name),
                pdfSource = { AndroidPdfTextSource(it) },
                rasterizer = { AndroidPageRasterizer(it) },
                newId = { "seed-$index" },
            )
            val result = importer.import(uri)
            assertTrue("${name} failed: ${result.exceptionOrNull()}", result.isSuccess)
        }

        // Give one of them progress, so the Continue Reading card has something to
        // show and the Library is exercised in its populated state.
        graph.repository.markOpened("seed-0")
        graph.repository.saveProgress("seed-0", ReadingPosition(1, 0, 40), 0.42)

        val library = graph.repository.observeLibrary().first()
        assertTrue("expected 4 books, got ${library.size}", library.size >= 4)
    }
}
