package app.folio.core.source

import app.folio.core.fixtures.FakeOcrEngine
import app.folio.core.model.plainText
import app.folio.core.reflow.LineAssembler
import app.folio.core.reflow.ParagraphAssembler
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OcrEngineTest {

    private val letter = PageGeometry(0, 612f, 792f)

    @Test
    fun `ocr output converts to text runs with positions preserved`() = runBlocking {
        val engine = FakeOcrEngine({ listOf("First recognised line", "Second recognised line") })
        val runs = engine.recognize(0, ByteArray(0)).toTextRuns(PageGeometry(0, 612f, 792f))

        assertEquals(2, runs.size)
        assertEquals("First recognised line", runs[0].text)
        assertEquals(72f, runs[0].x, 0.01f)
        assertTrue(runs[0].y > runs[1].y, "OCR line order must survive as descending y")
        assertTrue(runs.all { it.fontSize > 0f }, "font size must be inferred from box height")
    }

    @Test
    fun `blank recognised lines are dropped`() = runBlocking {
        val engine = FakeOcrEngine({ listOf("real text", "   ", "more text") })
        assertEquals(2, engine.recognize(0, ByteArray(0)).toTextRuns(PageGeometry(0, 612f, 792f)).size)
    }

    @Test
    fun `ocr output feeds the same reflow code as extracted text`() = runBlocking {
        // The point of the conversion: nothing downstream knows this was a scan.
        val engine = FakeOcrEngine({
            listOf(
                "Distributed systems are a collection of independent",
                "computers that appear to their users as a single",
                "coherent system, which is the whole point.",
            )
        })
        val runs = engine.recognize(0, ByteArray(0)).toTextRuns(PageGeometry(0, 612f, 792f))
        val page = PdfPage(PageGeometry(0, 612f, 792f), runs)
        val blocks = ParagraphAssembler().assemble(LineAssembler().assemble(page))

        val text = blocks.joinToString(" ") { it.plainText }
        assertTrue(
            text.contains("Distributed systems are a collection of independent computers"),
            "OCR lines did not reflow into a paragraph: $text",
        )
    }

    @Test
    fun `boxes are scaled from raster pixels into page space`() = runBlocking {
        // A 300 DPI raster of a Letter page is 2550x3300 px. Unscaled, every line
        // would sit far above the page and the header detector would delete the book.
        val engine = FakeOcrEngine({ listOf("a line near the top") })
        val raw = engine.recognize(0, ByteArray(0))
        val big = raw.copy(imageWidth = 2550f, imageHeight = 3300f)

        val run = big.toTextRuns(letter).single()
        assertTrue(run.y in 0f..792f, "y ${run.y} escaped the page")
        assertTrue(run.x in 0f..612f, "x ${run.x} escaped the page")
    }

    @Test
    fun `mean confidence is reported for the quality gate`() = runBlocking {
        val engine = FakeOcrEngine({ listOf("blurry") }, confidence = 0.31f)
        assertEquals(0.31f, engine.recognize(0, ByteArray(0)).meanConfidence, 0.001f)
    }
}
