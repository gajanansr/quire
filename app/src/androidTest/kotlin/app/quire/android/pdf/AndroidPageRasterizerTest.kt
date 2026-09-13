package app.quire.android.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.quire.android.DeviceFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PdfRenderer is native-backed: Robolectric cannot shadow it
 * (`NoSuchMethodError: FileDescriptor.getOwnerId$()`), so rasterization is only
 * verifiable on a real device image.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPageRasterizerTest {

    @Test
    fun reportsThePageCount() {
        assertTrue(AndroidPageRasterizer(DeviceFixtures.singleColumnPdf()).pageCount() > 0)
    }

    @Test
    fun rendersAPageToADecodablePng() = runBlocking {
        val bytes = AndroidPageRasterizer(DeviceFixtures.singleColumnPdf()).rasterize(0, 150)
        assertTrue("no image produced", bytes.size > 1000)
        assertTrue(
            "not a PNG",
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte(),
        )
    }

    @Test
    fun rejectsAnOutOfRangePage() {
        val result = runCatching {
            runBlocking { AndroidPageRasterizer(DeviceFixtures.singleColumnPdf()).rasterize(999, 150) }
        }
        assertTrue("expected a failure for page 999", result.isFailure)
        assertTrue(
            "expected an argument error, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is IllegalArgumentException,
        )
    }
}
