package app.quire.android.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.quire.android.importer.BookImporter

/**
 * Supplies [ImportWorker] its collaborators.
 *
 * WorkManager instantiates workers reflectively and only knows the two-argument
 * constructor, so anything a worker depends on has to arrive through a factory.
 * Hand-written rather than pulled from a DI library: there is one worker.
 */
class QuireWorkerFactory(
    private val importer: BookImporter,
    private val progress: ImportProgressStore,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        ImportWorker::class.java.name -> ImportWorker(appContext, workerParameters, importer, progress)
        else -> null   // null lets WorkManager fall back to its default factory
    }
}
