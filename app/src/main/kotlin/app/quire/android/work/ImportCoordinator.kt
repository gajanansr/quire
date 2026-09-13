package app.quire.android.work

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** What the import screen shows: a stage, and OCR page counts when relevant. */
data class ImportProgress(
    val stage: String,
    val pagesDone: Int,
    val pagesTotal: Int,
    val finished: Boolean,
    val failureReason: String?,
    val bookId: String?,
)

/**
 * Starts imports and reports their progress.
 *
 * Uses unique work keyed by book id with [ExistingWorkPolicy.KEEP], so tapping
 * "Add Book" twice on the same file does not import it twice.
 */
class ImportCoordinator(private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    fun start(uri: Uri, bookId: String = UUID.randomUUID().toString()): String {
        workManager.enqueueUniqueWork(
            ImportWorker.workName(bookId),
            ExistingWorkPolicy.KEEP,
            ImportWorker.request(uri, bookId),
        )
        return bookId
    }

    fun observe(bookId: String): Flow<ImportProgress?> =
        workManager.getWorkInfosForUniqueWorkFlow(ImportWorker.workName(bookId))
            .map { infos -> infos.firstOrNull()?.toProgress() }

    fun cancel(bookId: String) {
        workManager.cancelUniqueWork(ImportWorker.workName(bookId))
    }

    private fun WorkInfo.toProgress(): ImportProgress {
        // Once finished, `progress` is cleared, so the terminal stage comes from output.
        val source = if (state.isFinished) outputData else progress
        return ImportProgress(
            stage = when {
                state == WorkInfo.State.SUCCEEDED -> ImportProgressStore.STAGE_READY
                state == WorkInfo.State.FAILED -> ImportProgressStore.STAGE_FAILED
                else -> progress.getString(ImportWorker.KEY_STAGE)
                    ?: ImportProgressStore.STAGE_IMPORTING
            },
            pagesDone = progress.getInt(ImportWorker.KEY_PAGES_DONE, 0),
            pagesTotal = progress.getInt(ImportWorker.KEY_PAGES_TOTAL, 0),
            finished = state.isFinished,
            failureReason = source.getString(ImportWorker.KEY_FAILURE),
            bookId = source.getString(ImportWorker.KEY_BOOK_ID),
        )
    }
}
