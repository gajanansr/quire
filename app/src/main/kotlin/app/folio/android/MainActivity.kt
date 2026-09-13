package app.folio.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.folio.android.ui.nav.FolioRoot
import app.folio.android.ui.nav.HabitScreen
import app.folio.android.widget.FolioWidgets
import app.folio.android.ui.theme.FolioThemeName
import app.folio.android.ui.theme.themeNamed
import androidx.lifecycle.lifecycleScope
import app.folio.android.work.ImportCoordinator
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val graph by lazy { (application as FolioApp).graph }
    private val imports by lazy { ImportCoordinator(applicationContext) }

    /** Set when an import starts, so its progress can be observed. */
    private var activeImportId by mutableStateOf<String?>(null)

    /**
     * Where a widget tap wants to land, until the UI has taken it.
     *
     * Held as a pending value rather than a fixed start destination because Folio is
     * usually already running when a widget is tapped: the intent arrives at
     * [onNewIntent], not [onCreate], and a reader who tapped the streak widget,
     * navigated away, and tapped it again must be taken there a second time.
     */
    private var pendingHabitScreen by mutableStateOf<HabitScreen?>(null)

    /**
     * The system file picker.
     *
     * Folio reads the file once and copies it, so it asks for read access only and
     * does not persist a permission it will never use again.
     */
    private val pickBook = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) activeImportId = imports.start(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingHabitScreen = FolioWidgets.habitScreenOf(intent)
        setContent {
            val id = activeImportId
            val progress by remember(id) {
                if (id == null) flowOf(null) else imports.observe(id)
            }.collectAsState(initial = null)

            // The theme is read from storage rather than held in memory: a choice
            // that resets on every launch is not a setting.
            val settings by graph.habits.observeSettings()
                .collectAsState(initial = null)
            val theme = themeNamed(settings?.themeName)

            FolioRoot(
                repository = graph.repository,
                habitRepository = graph.habits,
                importProgress = progress,
                theme = theme,
                onThemeChange = { chosen ->
                    lifecycleScope.launch { graph.habits.setTheme(chosen.name) }
                },
                onChooseFile = { pickBook.launch(SUPPORTED_MIME_TYPES) },
                onDismissImport = { activeImportId = null },
                pendingHabitScreen = pendingHabitScreen,
                onHabitScreenOpened = { pendingHabitScreen = null },
            )
        }
    }

    /**
     * A widget tap on an already-running Folio.
     *
     * `setIntent` matters as much as reading it: without it `getIntent()` keeps
     * returning the one this activity was created with, and anything later that
     * re-reads it — a configuration change rebuilding the activity, for one — would
     * act on a destination the reader chose an hour ago.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingHabitScreen = FolioWidgets.habitScreenOf(intent)
    }

    private companion object {
        /**
         * Some providers report EPUB and TXT with vague types, so the generic type
         * is included too; the real format is settled by magic bytes after copying.
         */
        val SUPPORTED_MIME_TYPES = arrayOf(
            "application/epub+zip",
            "application/pdf",
            "text/plain",
            "application/octet-stream",
        )
    }
}
