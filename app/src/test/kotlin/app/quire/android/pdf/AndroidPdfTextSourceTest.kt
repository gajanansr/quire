package app.quire.android.pdf

import androidx.test.core.app.ApplicationProvider
import app.quire.core.fixtures.Fixtures
import app.quire.core.pdf.ScannedDetector
import app.quire.core.reflow.ReflowPipeline
import app.quire.core.model.plainText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android reader must agree with the JVM reader `:core` was developed against.
 * These mirror `:core`'s PdfBoxTextSourceTest deliberately.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidPdfTextSourceTest {

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `reports the page count`() {
        AndroidPdfTextSource(Fixtures.singleColumnPdf()).use {
            assertEquals(6, it.pageCount())
        }
    }

    @Test
    fun `extracts positioned runs with font metrics`() {
        AndroidPdfTextSource(Fixtures.singleColumnPdf()).use { s ->
            val runs = s.page(0).runs
            assertTrue("no runs extracted", runs.isNotEmpty())
            assertTrue("font size missing", runs.all { it.fontSize > 0f })
            assertTrue("glyph height missing", runs.all { it.height > 0f })
            assertTrue(runs.any { it.text.contains("Distributed") })
        }
    }

    @Test
    fun `page geometry matches US Letter`() {
        AndroidPdfTextSource(Fixtures.singleColumnPdf()).use { s ->
            val g = s.page(0).geometry
            assertEquals(612f, g.width, 1f)
            assertEquals(792f, g.height, 1f)
        }
    }

    @Test
    fun `bold is detected from the font name`() {
        AndroidPdfTextSource(Fixtures.chapteredPdf()).use { s ->
            val title = s.page(0).runs.first { it.text.contains("Weight of Silence") }
            assertTrue("bold not flagged, font was ${title.fontName}", title.bold)
        }
    }

    @Test
    fun `a scanned page yields effectively no runs`() {
        AndroidPdfTextSource(Fixtures.imageOnlyPdf()).use { s ->
            assertTrue(s.page(0).charCount < 20)
        }
    }

    @Test
    fun `scanned detection agrees with the jvm implementation`() {
        AndroidPdfTextSource(Fixtures.imageOnlyPdf()).use { s ->
            assertTrue("scan not detected on device reader", ScannedDetector().classify(s).isScanned)
        }
        AndroidPdfTextSource(Fixtures.singleColumnPdf()).use { s ->
            assertTrue("text PDF misread as scanned", !ScannedDetector().classify(s).isScanned)
        }
    }

    @Test
    fun `the core reflow pipeline runs unchanged on the android reader`() {
        // The whole point of the interface: the logic proven on the JVM is the logic
        // that runs on device, with no Android-specific branch anywhere in it.
        AndroidPdfTextSource(Fixtures.headerFooterPdf()).use { s ->
            val result = ReflowPipeline().reflow(s)
            val text = result.blocks.joinToString(" ") { it.plainText }
            assertTrue("running header survived", !text.contains("A HISTORY OF QUIET THINGS"))
            assertTrue(
                "hyphenation unresolved: ${text.take(140)}",
                text.contains("Distributed systems are a collection of"),
            )
        }
    }

    @Test
    fun `a structurally corrupt pdf fails on open rather than producing garbage`() {
        val result = runCatching { AndroidPdfTextSource(Fixtures.corruptPdf()).use { it.pageCount() } }
        assertTrue("expected corrupt PDF to fail loudly", result.isFailure)
    }
}
