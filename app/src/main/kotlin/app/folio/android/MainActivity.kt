package app.folio.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import app.folio.android.ui.nav.FolioRoot
import app.folio.android.work.ImportCoordinator
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val graph by lazy { (application as FolioApp).graph }
    private val imports by lazy { ImportCoordinator(applicationContext) }

    /**
     * The system file picker. Folio reads the file once and copies it, so it asks
     * for read access only and does not persist a permission it will never reuse.
     */
    private val pickBook = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch { imports.start(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FolioRoot(
                repository = graph.repository,
                onAddBook = { pickBook.launch(SUPPORTED_MIME_TYPES) },
            )
        }
    }

    private companion object {
        /**
         * Some providers report EPUB and TXT with vague types, so the wildcard is
         * included and the real format is settled by magic bytes after copying.
         */
        val SUPPORTED_MIME_TYPES = arrayOf(
            "application/epub+zip",
            "application/pdf",
            "text/plain",
            "application/octet-stream",
        )
    }
}
