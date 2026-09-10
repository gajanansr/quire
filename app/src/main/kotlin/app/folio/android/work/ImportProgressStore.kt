package app.folio.android.work

import app.folio.android.data.BookStore
import app.folio.core.model.FailureReason
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The stage an import reached, persisted beside the book.
 *
 * WorkManager restarts a worker after process death, and OCR over a few hundred
 * scanned pages is far too expensive to redo. Recording the stage on disk lets a
 * restarted import pick up rather than start over, and lets the Library show a book
 * as still processing instead of silently missing.
 */
@Serializable
data class ImportState(
    val bookId: String,
    val stage: String,
    val sourceUri: String,
    val pagesDone: Int = 0,
    val pagesTotal: Int = 0,
    val failure: FailureReason? = null,
    val updatedAt: Long = 0L,
)

class ImportProgressStore(private val store: BookStore) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(bookId: String) = File(store.bookDir(bookId), "processing.json")

    fun save(state: ImportState) {
        val f = file(state.bookId)
        f.parentFile?.mkdirs()
        // Write then rename: a process killed mid-write must not leave a truncated
        // state file that reads as corrupt on restart.
        val tmp = File(f.parentFile, "processing.json.tmp")
        tmp.writeText(json.encodeToString(state))
        tmp.renameTo(f)
    }

    fun load(bookId: String): ImportState? {
        val f = file(bookId)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<ImportState>(f.readText()) }.getOrNull()
    }

    fun clear(bookId: String) {
        file(bookId).delete()
    }

    /** Imports that were mid-flight when the process died. */
    fun unfinished(): List<ImportState> =
        store.allBookIds()
            .mapNotNull { load(it) }
            .filter { it.stage != STAGE_READY && it.failure == null }

    companion object {
        const val STAGE_IMPORTING = "importing"
        const val STAGE_DETECTING_FORMAT = "detectingFormat"
        const val STAGE_EXTRACTING = "extracting"
        const val STAGE_OCR = "ocr"
        const val STAGE_DETECTING_STRUCTURE = "detectingStructure"
        const val STAGE_NORMALIZING = "normalizing"
        const val STAGE_READY = "ready"
        const val STAGE_FAILED = "failed"
    }
}
