package app.quire.android

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import app.quire.android.notify.ReminderWorkerFactory
import app.quire.android.data.BookRepository
import app.quire.android.data.BookStore
import app.quire.android.data.HabitRepository
import app.quire.android.data.QuireDatabase
import app.quire.android.importer.BookImporter
import app.quire.android.importer.ContentResolverUriOpener
import app.quire.android.pdf.AndroidPageRasterizer
import app.quire.android.pdf.AndroidPdfTextSource
import app.quire.android.work.QuireWorkerFactory
import app.quire.android.widget.QuireWidgets
import app.quire.android.work.ImportProgressStore
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
 * Implements [Configuration.Provider] because [app.quire.android.work.ImportWorker]
 * takes constructor dependencies, and WorkManager instantiates workers reflectively
 * unless given a factory.
 */
class QuireApp : Application(), Configuration.Provider {

    lateinit var graph: QuireGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // PdfBox-Android loads its font resources from assets and must be told the
        // context before any document is opened.
        PDFBoxResourceLoader.init(this)
        graph = QuireGraph(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            // Two factories rather than one that knows about both jobs: importing
            // and reminding share no dependencies, and DelegatingWorkerFactory asks
            // each in turn until one recognises the worker.
            .setWorkerFactory(
                DelegatingWorkerFactory().apply {
                    addFactory(QuireWorkerFactory(graph.importer, graph.importProgress))
                    addFactory(ReminderWorkerFactory(graph.habits, graph.repository))
                }
            )
            .build()
}

/** Everything with an application lifetime, built once. */
class QuireGraph(context: Context) {

    private val app = context.applicationContext

    // Everything is lazy: Application.onCreate runs on the main thread at launch,
    // and neither opening a database nor constructing a recogniser belongs there.
    val database: QuireDatabase by lazy {
        Room.databaseBuilder(app, QuireDatabase::class.java, "quire.db")
            .addMigrations(
                QuireDatabase.MIGRATION_1_2,
                QuireDatabase.MIGRATION_2_3,
                QuireDatabase.MIGRATION_3_4,
                QuireDatabase.MIGRATION_4_5,
                QuireDatabase.MIGRATION_5_6,
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
        widgetScope.launch { QuireWidgets.refresh(app) }
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
