package app.folio.android.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.folio.core.source.ImageEnhancer
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Prepares a rendered page for recognition.
 *
 * Pages went to OCR exactly as rendered. That is fine for a clean digital scan and
 * poor for a photographed one, where the page is grey rather than white, lit
 * unevenly, and low in contrast — the conditions recognisers handle worst.
 *
 * Two steps, both long-established for document images and neither destructive:
 *
 * - **Greyscale.** Colour carries no information in a page of text, and dropping it
 *   removes the colour fringing that phone cameras and JPEG compression introduce
 *   around letter edges.
 * - **Auto-levels.** The darkest few per cent of pixels are mapped to black and the
 *   lightest few to white, stretching everything between. A photograph whose page is
 *   mid-grey becomes a page that is white with black text on it.
 *
 * Percentiles rather than the true minimum and maximum, because one dust speck and
 * one blown highlight would otherwise define the whole range and nothing would move.
 *
 * Whether this is used at all is not decided here: `PdfPipeline` tries both paths on
 * a sample of pages and keeps whichever the recogniser is more confident about, so a
 * book that gains nothing is never put through it.
 */
class PagePreprocessor : ImageEnhancer {

    override suspend fun enhance(image: ByteArray): ByteArray {
        // A page that will not decode is returned untouched rather than dropped:
        // recognition may still make something of the original bytes.
        val source = runCatching {
            BitmapFactory.decodeByteArray(image, 0, image.size)
        }.getOrNull() ?: return image

        return try {
            val levels = levelsOf(source) ?: return image
            val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            applyLevels(source, out, levels)
            ByteArrayOutputStream().use { sink ->
                out.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, sink)
                out.recycle()
                sink.toByteArray()
            }
        } catch (_: OutOfMemoryError) {
            // A very large page is worth recognising unenhanced rather than not at
            // all, and the import is already holding a full-page bitmap elsewhere.
            image
        } finally {
            source.recycle()
        }
    }

    /** The grey values that should become black and white. */
    private data class Levels(val low: Int, val high: Int)

    /**
     * Reads the page's histogram from a sample of its rows.
     *
     * Sampled because percentiles do not need every pixel and a full-page read at
     * 300 DPI is tens of megabytes. Row by row for the same reason.
     */
    private fun levelsOf(bitmap: Bitmap): Levels? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        val histogram = IntArray(256)
        val row = IntArray(width)
        var counted = 0L

        var y = 0
        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                histogram[luminance(pixel)]++
                counted++
            }
            y += ROW_STRIDE
        }
        if (counted == 0L) return null

        val lowTarget = (counted * CLIP_FRACTION).toLong()
        val highTarget = (counted * (1f - CLIP_FRACTION)).toLong()

        var running = 0L
        var low = 0
        var high = 255
        for (value in 0..255) {
            running += histogram[value]
            if (running >= lowTarget) { low = value; break }
        }
        running = 0L
        for (value in 0..255) {
            running += histogram[value]
            if (running >= highTarget) { high = value; break }
        }

        // A page with no range to stretch — a blank sheet, or one already at full
        // contrast — is left alone rather than amplified into noise.
        return if (high - low < MIN_RANGE) null else Levels(low, high)
    }

    private fun applyLevels(source: Bitmap, target: Bitmap, levels: Levels) {
        val lut = IntArray(256) { value ->
            val scaled = (value - levels.low).toFloat() / (levels.high - levels.low)
            (scaled * 255f).roundToInt().coerceIn(0, 255)
        }

        val width = source.width
        val row = IntArray(width)
        for (y in 0 until source.height) {
            source.getPixels(row, 0, width, 0, y, width, 1)
            for (i in row.indices) {
                val grey = lut[luminance(row[i])]
                row[i] = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
            }
            target.setPixels(row, 0, width, 0, y, width, 1)
        }
    }

    /** Rec. 601 luma, which is what greyscale conversion for text should use. */
    private fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
    }

    private companion object {
        /** Every eighth row is plenty to place a percentile. */
        const val ROW_STRIDE = 8

        /** Proportion of pixels clipped at each end before stretching. */
        const val CLIP_FRACTION = 0.02f

        /** Below this spread there is nothing to stretch, only noise to amplify. */
        const val MIN_RANGE = 16

        /** PNG ignores this, but the parameter is required. */
        const val PNG_QUALITY = 100
    }
}
