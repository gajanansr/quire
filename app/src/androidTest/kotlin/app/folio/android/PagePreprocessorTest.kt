package app.folio.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.folio.android.ocr.PagePreprocessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Preprocessing a page before recognition.
 *
 * Bitmaps are Android, so this runs on a device. What it checks is a property rather
 * than pixels: a washed-out page should come back using the full range from black to
 * white, and a page already using it should not be disturbed.
 */
class PagePreprocessorTest {

    private val preprocessor = PagePreprocessor()

    /** A page of "text" drawn at [ink] on a background of [paper]. */
    private fun page(paper: Int, ink: Int, width: Int = 200, height: Int = 120): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            // Every eighth band is a line of text; the rest is paper.
            val isTextRow = (y / 4) % 3 == 0
            for (x in row.indices) {
                val isGlyph = isTextRow && (x / 3) % 2 == 0
                val v = if (isGlyph) ink else paper
                row[x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    /** Darkest and lightest grey actually present. */
    private fun rangeOf(bytes: ByteArray): Pair<Int, Int> {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        var low = 255
        var high = 0
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (pixel in row) {
                val v = pixel and 0xFF
                if (v < low) low = v
                if (v > high) high = v
            }
        }
        bitmap.recycle()
        return low to high
    }

    @Test
    fun aWashedOutPageIsStretchedToFullRange() = runBlocking {
        // A photographed page: mid-grey paper, dark-grey ink, nothing near either end.
        val before = page(paper = 170, ink = 90)
        val (lowBefore, highBefore) = rangeOf(before)
        assertTrue("fixture is not low contrast", highBefore - lowBefore < 100)

        val (low, high) = rangeOf(preprocessor.enhance(before))
        assertTrue("range did not widen: $low..$high", high - low > highBefore - lowBefore)
        assertTrue("paper did not reach white: $high", high >= 240)
        assertTrue("ink did not reach black: $low", low <= 15)
    }

    @Test
    fun aPageAlreadyAtFullContrastIsLeftAlone() = runBlocking {
        // A clean digital scan. There is nothing to stretch, and amplifying it would
        // only sharpen noise, so the bytes come back untouched.
        val before = page(paper = 255, ink = 0)
        val after = preprocessor.enhance(before)
        val (low, high) = rangeOf(after)
        assertEquals(0, low)
        assertEquals(255, high)
    }

    @Test
    fun aBlankPageIsNotAmplifiedIntoNoise() = runBlocking {
        // No range to stretch. Scaling it would turn sensor noise into "text".
        val blank = page(paper = 200, ink = 200)
        val (low, high) = rangeOf(preprocessor.enhance(blank))
        assertTrue("a blank page gained structure: $low..$high", high - low < 16)
    }

    @Test
    fun bytesThatAreNotAnImageComeBackUnchanged() = runBlocking {
        // Recognition may still make something of the original, so a page that will
        // not decode is passed along rather than dropped.
        val garbage = byteArrayOf(1, 2, 3, 4, 5)
        assertTrue(preprocessor.enhance(garbage).contentEquals(garbage))
    }
}
