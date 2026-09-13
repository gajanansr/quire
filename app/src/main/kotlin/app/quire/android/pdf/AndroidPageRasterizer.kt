package app.quire.android.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.quire.core.source.PageRasterizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Renders PDF pages to images for OCR, using Android's built-in [PdfRenderer].
 *
 * Deliberately opens, renders, and closes one page at a time. A 400-page scan
 * rendered at 300 DPI is several gigabytes of bitmap if held together, so each page
 * is encoded and its bitmap recycled before the next is touched. That makes this
 * slower than batching and is the right trade: OCR is already the slow step, and an
 * OutOfMemoryError mid-import loses the whole book.
 *
 * PdfRenderer needs a seekable file descriptor, so it reads Quire's own copy of the
 * book rather than the user's original URI.
 */
class AndroidPageRasterizer(private val file: File) : PageRasterizer {

    override suspend fun rasterize(pageIndex: Int, dpi: Int): ByteArray =
        withContext(Dispatchers.Default) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    require(pageIndex in 0 until renderer.pageCount) {
                        "page $pageIndex out of range (${renderer.pageCount} pages)"
                    }
                    renderer.openPage(pageIndex).use { page ->
                        // PDF points are 1/72 inch, so scale is dpi/72.
                        val scale = dpi / POINTS_PER_INCH
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        // PdfRenderer composites onto whatever is already there, so an
                        // unpainted bitmap leaves transparent gaps that OCR reads as noise.
                        bitmap.eraseColor(Color.WHITE)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            ByteArrayOutputStream().use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                out.toByteArray()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }

    fun pageCount(): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }

    private companion object {
        const val POINTS_PER_INCH = 72f
    }
}
