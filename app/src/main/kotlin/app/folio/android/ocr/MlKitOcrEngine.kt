package app.folio.android.ocr

import android.graphics.BitmapFactory
import app.folio.core.source.OcrEngine
import app.folio.core.source.OcrLine
import app.folio.core.source.OcrPage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device text recognition via ML Kit.
 *
 * Uses the **bundled** Latin model. The Play-Services variant downloads its model at
 * first use, which would make importing a scanned book require a network — breaking
 * the guarantee that nothing about the user's books leaves the device, and failing
 * outright offline.
 *
 * ML Kit reports boxes in image space with a top-left origin. They are flipped to the
 * bottom-left origin the pipeline uses, so recognised text lands in the same
 * coordinate space as text extracted from a PDF and reflows through identical code.
 */
class MlKitOcrEngine : OcrEngine {

    /**
     * Created on first use, not at construction.
     *
     * `TextRecognition.getClient` needs ML Kit's context to be initialized, which
     * only happens in a real app process. Building it eagerly makes merely
     * constructing the object graph fail everywhere else — including under
     * Robolectric, where it took out every JVM-side Android test at once.
     */
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognize(pageIndex: Int, image: ByteArray): OcrPage {
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size)
            ?: error("could not decode page $pageIndex for recognition")
        val height = bitmap.height
        val width = bitmap.width

        return try {
            val text = suspendCancellableCoroutine { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }

            val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(
                    text = line.text,
                    x = box.left.toFloat(),
                    y = (height - box.bottom).toFloat(),
                    width = box.width().toFloat(),
                    height = box.height().toFloat(),
                    // ML Kit exposes confidence per line on recent versions; where it
                    // is absent, treat recognition as nominal rather than as failed.
                    confidence = line.confidence ?: NOMINAL_CONFIDENCE,
                )
            }

            OcrPage(
                pageIndex = pageIndex,
                lines = lines,
                meanConfidence = if (lines.isEmpty()) 0f
                else lines.map { it.confidence }.average().toFloat(),
                imageWidth = width.toFloat(),
                imageHeight = height.toFloat(),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val NOMINAL_CONFIDENCE = 0.8f
    }
}
