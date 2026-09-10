package app.folio.android

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import app.folio.android.data.BookRepository
import app.folio.android.data.BookStore
import app.folio.android.data.HabitRepository
import app.folio.android.data.FolioDatabase
import app.folio.android.importer.BookImporter
import app.folio.android.importer.ContentResolverUriOpener
import app.folio.android.ocr.MlKitOcrEngine
import app.folio.android.pdf.AndroidPageRasterizer
import app.folio.android.pdf.AndroidPdfTextSource
import app.folio.android.work.FolioWorkerFactory
import app.folio.android.work.ImportProgressStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * The object graph.
 *
 * Hand-wired rather than assembled by a DI framework: there are six objects and one
 * lifetime, and a container would add indirection without removing any decisions.
 *
 * Implements [Configuration.Provider] because [app.folio.android.work.ImportWorker]
 * takes constructor dependencies, and WorkManager instantiates workers reflectively
 * unless given a factory.
 */
class FolioApp : Application(), Configuration.Provider {

    lateinit var graph: FolioGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // PdfBox-Android loads its font resources from assets and must be told the
        // context before any document is opened.
        PDFBoxResourceLoader.init(this)
        graph = FolioGraph(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(FolioWorkerFactory(graph.importer, graph.importProgress))
            .build()
}

/** Everything with an application lifetime, built once. */
class FolioGraph(context: Context) {

    private val app = context.applicationContext

    // Everything is lazy: Application.onCreate runs on the main thread at launch,
    // and neither opening a database nor constructing a recogniser belongs there.
    val database: FolioDatabase by lazy {
        Room.databaseBuilder(app, FolioDatabase::class.java, "folio.db")
            .addMigrations(FolioDatabase.MIGRATION_1_2, FolioDatabase.MIGRATION_2_3)
            .build()
    }

    val store: BookStore by lazy { BookStore(app.filesDir) }

    val repository: BookRepository by lazy { BookRepository(database, store) }

    val habits: HabitRepository by lazy { HabitRepository(database) }

    val importProgress: ImportProgressStore by lazy { ImportProgressStore(store) }

    val importer: BookImporter by lazy {
        BookImporter(
            store = store,
            repository = repository,
            opener = ContentResolverUriOpener(app),
            pdfSource = { AndroidPdfTextSource(it) },
            ocr = MlKitOcrEngine(),
            rasterizer = { AndroidPageRasterizer(it) },
        )
    }
}
