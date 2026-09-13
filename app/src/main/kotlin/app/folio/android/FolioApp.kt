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
import app.folio.android.pdf.AndroidPageRasterizer
import app.folio.android.pdf.AndroidPdfTextSource
import app.folio.android.work.FolioWorkerFactory
import app.folio.android.widget.FolioWidgets
import app.folio.android.work.ImportProgressStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
            .addMigrations(
                FolioDatabase.MIGRATION_1_2,
                FolioDatabase.MIGRATION_2_3,
                FolioDatabase.MIGRATION_3_4,
                FolioDatabase.MIGRATION_4_5,
            )
            .build()
    }

    val store: BookStore by lazy { BookStore(app.filesDir) }

    /**
     * The one place that knows widgets exist.
     *
     * Both repositories announce their writes and neither knows what listens; this
     * is what turns an announcement into a redraw. Without it a pinned widget would
     * be correct only on the platform's half-hourly update, which is to say it would
     * be wrong for most of the time a reader is actually looking at it.
     *
     * Dispatched off the caller's thread on purpose. The Reader persists its
     * position from a main-thread coroutine, and asking the AppWidgetManager which
     * widgets are pinned is a binder call; it is fast, but it is not the main
     * thread's work, and the scanned-PDF viewer saves on every page turn.
     */
    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val refreshWidgets: () -> Unit = {
        widgetScope.launch { FolioWidgets.refresh(app) }
    }

    val repository: BookRepository by lazy {
        BookRepository(database, store, onDataChanged = refreshWidgets)
    }

    val habits: HabitRepository by lazy {
        HabitRepository(database, onDataChanged = refreshWidgets)
    }

    val importProgress: ImportProgressStore by lazy { ImportProgressStore(store) }

    val importer: BookImporter by lazy {
        BookImporter(
            store = store,
            repository = repository,
            opener = ContentResolverUriOpener(app),
            pdfSource = { AndroidPdfTextSource(it) },
            rasterizer = { AndroidPageRasterizer(it) },
        )
    }
}
