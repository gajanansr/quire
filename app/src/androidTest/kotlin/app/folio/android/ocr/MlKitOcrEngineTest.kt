package app.folio.android.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.folio.android.DeviceFixtures
import app.folio.android.pdf.AndroidPageRasterizer
import app.folio.core.FolioConstants
import app.folio.core.model.plainText
import app.folio.core.reflow.LineAssembler
import app.folio.core.reflow.ParagraphAssembler
import app.folio.core.source.PageGeometry
import app.folio.core.source.PdfPage
import app.folio.core.source.toTextRuns
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The only place OCR correctness can actually be established.
 *
 * Everywhere else in the suite OCR is a fake returning scripted text, which proves
 * the wiring but says nothing about recognition. This runs the real bundled ML Kit
 * model over a real rendered page of `scanned.pdf` — a PDF built by rasterizing
 * text, so the ground truth is known.
 */
@RunWith(AndroidJUnit4::class)
class MlKitOcrEngineTest {

    private val engine = MlKitOcrEngine()

    @Test
    fun recognisesTextFromARenderedScan() = runBlocking {
        val scan = DeviceFixtures.scannedPdf()
        val image = AndroidPageRasterizer(scan).rasterize(0, FolioConstants.OCR_RENDER_DPI)

        val page = engine.recognize(0, image)

        assertTrue("no lines recognised", page.lines.isNotEmpty())
        val text = page.lines.joinToString(" ") { it.text }
        assertTrue(
            "expected the known ground truth, got: ${text.take(200)}",
            text.contains("Distributed", ignoreCase = true),
        )
        assertTrue(
            "expected 'systems' in: ${text.take(200)}",
            text.contains("systems", ignoreCase = true),
        )
    }

    @Test
    fun reportsUsableConfidence() = runBlocking {
        val image = AndroidPageRasterizer(DeviceFixtures.scannedPdf())
            .rasterize(0, FolioConstants.OCR_RENDER_DPI)
        val page = engine.recognize(0, image)
        assertTrue(
            "confidence ${page.meanConfidence} should clear the quality gate on a clean scan",
            page.meanConfidence >= FolioConstants.MIN_OCR_CONFIDENCE,
        )
    }

    @Test
    fun recognisedLinesReflowThroughTheCorePipeline() = runBlocking {
        // The architectural claim, proven on device: OCR output wears the same shape
        // as extracted text, so :core's reflow runs over it with no OCR-specific path.
        val image = AndroidPageRasterizer(DeviceFixtures.scannedPdf())
            .rasterize(0, FolioConstants.OCR_RENDER_DPI)
        val runs = engine.recognize(0, image).toTextRuns(PageGeometry(0, 612f, 792f))
        assertTrue("no runs produced", runs.isNotEmpty())

        val pdfPage = PdfPage(PageGeometry(0, 612f, 792f), runs)
        val blocks = ParagraphAssembler().assemble(LineAssembler().assemble(pdfPage))
        val text = blocks.joinToString(" ") { it.plainText }

        assertTrue("reflow produced nothing from OCR output", blocks.isNotEmpty())
        assertTrue(
            "reflowed OCR text lost the ground truth: ${text.take(200)}",
            text.contains("Distributed", ignoreCase = true),
        )
    }

    @Test
    fun ordersRecognisedLinesTopToBottom() = runBlocking {
        val image = AndroidPageRasterizer(DeviceFixtures.scannedPdf())
            .rasterize(0, FolioConstants.OCR_RENDER_DPI)
        val runs = engine.recognize(0, image).toTextRuns(PageGeometry(0, 612f, 792f))
        val assembled = LineAssembler().assemble(PdfPage(PageGeometry(0, 612f, 792f), runs))
        val ys = assembled.map { it.y }
        assertTrue("lines not ordered top to bottom", ys == ys.sortedDescending())
    }
}
