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

    @Test
    fun `ocr output converts to text runs with positions preserved`() = runBlocking {
        val engine = FakeOcrEngine({ listOf("First recognised line", "Second recognised line") })
        val runs = engine.recognize(0, ByteArray(0)).toTextRuns()

        assertEquals(2, runs.size)
        assertEquals("First recognised line", runs[0].text)
        assertEquals(72f, runs[0].x, 0.01f)
        assertTrue(runs[0].y > runs[1].y, "OCR line order must survive as descending y")
        assertTrue(runs.all { it.fontSize > 0f }, "font size must be inferred from box height")
    }

    @Test
    fun `blank recognised lines are dropped`() = runBlocking {
        val engine = FakeOcrEngine({ listOf("real text", "   ", "more text") })
        assertEquals(2, engine.recognize(0, ByteArray(0)).toTextRuns().size)
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
        val runs = engine.recognize(0, ByteArray(0)).toTextRuns()
        val page = PdfPage(PageGeometry(0, 612f, 792f), runs)
        val blocks = ParagraphAssembler().assemble(LineAssembler().assemble(page))

        val text = blocks.joinToString(" ") { it.plainText }
        assertTrue(
            text.contains("Distributed systems are a collection of independent computers"),
            "OCR lines did not reflow into a paragraph: $text",
        )
    }

    @Test
    fun `mean confidence is reported for the quality gate`() = runBlocking {
        val engine = FakeOcrEngine({ listOf("blurry") }, confidence = 0.31f)
        assertEquals(0.31f, engine.recognize(0, ByteArray(0)).meanConfidence, 0.001f)
    }
}
