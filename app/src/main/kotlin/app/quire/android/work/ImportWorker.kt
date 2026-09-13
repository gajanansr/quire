package app.quire.android.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.quire.android.data.BookStore
import app.quire.android.importer.BookImporter
import app.quire.android.importer.ImportFailure
import app.quire.core.model.ProcessingStatus

/**
 * Runs an import off the main thread, surviving process death.
 *
 * Book processing can take minutes — a few hundred scanned pages through OCR is the
 * worst case — so it cannot sit on the UI thread and cannot be tied to an Activity.
 * WorkManager gives it a lifecycle of its own and restarts it if the process is
 * killed; [ImportProgressStore] records how far it got so the restart resumes.
 *
 * Enqueued as unique work per book id, so a double-tap on "Add Book" cannot import
 * the same file twice.
 */
class ImportWorker(
    context: Context,
    params: WorkerParameters,
    private val importer: BookImporter,
    private val progress: ImportProgressStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI) ?: return Result.failure(
            workDataOf(KEY_FAILURE to "MISSING_URI")
        )
        val bookId = inputData.getString(KEY_BOOK_ID).orEmpty()

        val outcome = importer.import(Uri.parse(uriString)) { status ->
            setProgressAsyncSafe(status)
            progress.save(
                ImportState(
                    bookId = bookId,
                    stage = status.stageName(),
                    sourceUri = uriString,
                    pagesDone = (status as? ProcessingStatus.Ocr)?.pagesDone ?: 0,
                    pagesTotal = (status as? ProcessingStatus.Ocr)?.pagesTotal ?: 0,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }

        return outcome.fold(
            onSuccess = { book ->
                progress.clear(book.id)
                Result.success(workDataOf(KEY_BOOK_ID to book.id))
            },
            onFailure = { error ->
                val reason = (error as? ImportFailure)?.reason?.name ?: "EXTRACTION_FAILED"
                // The directory is already gone; clear any state that outlived it.
                progress.clear(bookId)
                Result.failure(workDataOf(KEY_FAILURE to reason))
            },
        )
    }

    private fun setProgressAsyncSafe(status: ProcessingStatus) {
        runCatching {
            setProgressAsync(
                workDataOf(
                    KEY_STAGE to status.stageName(),
                    KEY_PAGES_DONE to ((status as? ProcessingStatus.Ocr)?.pagesDone ?: 0),
                    KEY_PAGES_TOTAL to ((status as? ProcessingStatus.Ocr)?.pagesTotal ?: 0),
                )
            )
        }
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_BOOK_ID = "bookId"
        const val KEY_STAGE = "stage"
        const val KEY_PAGES_DONE = "pagesDone"
        const val KEY_PAGES_TOTAL = "pagesTotal"
        const val KEY_FAILURE = "failure"

        fun workName(bookId: String) = "import-$bookId"

        fun request(uri: Uri, bookId: String) =
            OneTimeWorkRequestBuilder<ImportWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_URI, uri.toString())
                        .putString(KEY_BOOK_ID, bookId)
                        .build()
                )
                .build()
    }
}

/** Maps a pipeline state to the stage string persisted and reported. */
fun ProcessingStatus.stageName(): String = when (this) {
    ProcessingStatus.Idle -> "idle"
    ProcessingStatus.Importing -> ImportProgressStore.STAGE_IMPORTING
    ProcessingStatus.DetectingFormat -> ImportProgressStore.STAGE_DETECTING_FORMAT
    ProcessingStatus.Extracting -> ImportProgressStore.STAGE_EXTRACTING
    is ProcessingStatus.Ocr -> ImportProgressStore.STAGE_OCR
    ProcessingStatus.DetectingStructure -> ImportProgressStore.STAGE_DETECTING_STRUCTURE
    ProcessingStatus.Normalizing -> ImportProgressStore.STAGE_NORMALIZING
    ProcessingStatus.Ready -> ImportProgressStore.STAGE_READY
    is ProcessingStatus.Failed -> ImportProgressStore.STAGE_FAILED
}
