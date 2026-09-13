package app.quire.core.reflow

import app.quire.core.QuireConstants
import app.quire.core.fixtures.Fixtures
import app.quire.core.fixtures.PdfBoxTextSource
import app.quire.core.model.ContentBlock
import app.quire.core.model.plainText
import app.quire.core.source.OutlineEntry
import app.quire.core.source.PageGeometry
import app.quire.core.source.PdfPage
import app.quire.core.source.PdfTextSource
import app.quire.core.source.TextRun
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReflowPipelineTest {

    private val pipeline = ReflowPipeline()

    /** An in-memory source, for cases awkward to express as a real PDF. */
    private class FakeSource(private val pages: List<PdfPage>) : PdfTextSource {
        override fun pageCount() = pages.size
        override fun page(index: Int) = pages[index]
        override fun outline(): List<OutlineEntry> = emptyList()
        override fun isEncrypted() = false
        override fun close() {}
    }

    private fun run(text: String, x: Float, y: Float, size: Float = 11f) = TextRun(
        text = text, x = x, y = y, width = text.length * size * 0.5f, height = size,
        fontSize = size, fontName = "Helvetica", bold = false, italic = false,
    )

    @Test
    fun `reflows the single column fixture into paragraphs`() {
        PdfBoxTextSource(Fixtures.singleColumnPdf()).use { s ->
            val result = pipeline.reflow(s)
            assertTrue(result.blocks.isNotEmpty(), "no blocks produced")
            assertTrue(result.blocks.all { it is ContentBlock.Paragraph || it is ContentBlock.Heading })
            assertTrue(result.confidence >= QuireConstants.MIN_REFLOW_CONFIDENCE,
                "confidence ${result.confidence} below threshold on a clean PDF")
        }
    }

    @Test
    fun `removes furniture and resolves hyphens on the header footer fixture`() {
        PdfBoxTextSource(Fixtures.headerFooterPdf()).use { s ->
            val text = pipeline.reflow(s).blocks.joinToString(" ") { it.plainText }
            assertTrue(!text.contains("A HISTORY OF QUIET THINGS"), "running header survived")
            assertTrue(text.contains("Distributed systems are a collection of"),
                "hyphenation unresolved: ${text.take(140)}")
            assertTrue(!Regex("(^|\\s)\\d+(\\s|$)").containsMatchIn(text),
                "a bare page number survived: ${text.take(140)}")
        }
    }

    @Test
    fun `records a page break per page`() {
        PdfBoxTextSource(Fixtures.singleColumnPdf()).use { s ->
            assertEquals(s.pageCount(), pipeline.reflow(s).pageBreaks.size)
        }
    }

    @Test
    fun `keeps two column text in reading order`() {
        PdfBoxTextSource(Fixtures.twoColumnPdf()).use { s ->
            val text = pipeline.reflow(s).blocks.joinToString(" ") { it.plainText }
            // The left column's opening must appear before the right column's opening.
            val left = text.indexOf("Distributed systems are a")
            val right = text.indexOf("The consequences of this")
            assertTrue(left >= 0 && right >= 0, "expected both columns in the output")
            assertTrue(left < right, "columns came out interleaved or reversed")
        }
    }

    @Test
    fun `joins a paragraph continuing across a page boundary`() {
        val p1 = PdfPage(PageGeometry(0, 612f, 792f), listOf(
            run("A sentence that runs right up to the very bottom of", 72f, 100f),
            run("this page and continues without any terminal stop", 72f, 84f),
        ))
        val p2 = PdfPage(PageGeometry(1, 612f, 792f), listOf(
            run("onto the following page before it finally ends.", 72f, 720f),
        ))
        val text = pipeline.reflow(FakeSource(listOf(p1, p2))).blocks
            .joinToString(" | ") { it.plainText }
        assertTrue(text.contains("continues without any terminal stop onto the following page"),
            "paragraph was split at the page boundary: $text")
    }

    @Test
    fun `joins a word hyphenated across a page boundary`() {
        val p1 = PdfPage(PageGeometry(0, 612f, 792f), listOf(
            run("The architecture is fundamentally dis-", 72f, 100f),
        ))
        val p2 = PdfPage(PageGeometry(1, 612f, 792f), listOf(
            run("tributed across many machines.", 72f, 720f),
        ))
        val text = pipeline.reflow(FakeSource(listOf(p1, p2))).blocks
            .joinToString(" ") { it.plainText }
        assertTrue(text.contains("fundamentally distributed across"),
            "cross-page hyphenation unresolved: $text")
    }

    @Test
    fun `does not join across a page boundary when the sentence ended`() {
        val p1 = PdfPage(PageGeometry(0, 612f, 792f), listOf(
            run("A complete sentence ending here.", 72f, 100f),
        ))
        val p2 = PdfPage(PageGeometry(1, 612f, 792f), listOf(
            run("A new sentence starting there.", 72f, 720f),
        ))
        val blocks = pipeline.reflow(FakeSource(listOf(p1, p2))).blocks
        assertEquals(2, blocks.count { it is ContentBlock.Paragraph },
            "merged two complete sentences across a page break")
    }

    @Test
    fun `a document with no extractable text reports low confidence and stays empty`() {
        val result = pipeline.reflow(FakeSource(listOf(
            PdfPage(PageGeometry(0, 612f, 792f), emptyList())
        )))
        assertTrue(result.confidence < QuireConstants.MIN_REFLOW_CONFIDENCE)
        assertTrue(result.blocks.isEmpty())
    }

    @Test
    fun `low confidence still returns the extracted text rather than nothing`() {
        // A page of disconnected fragments reflows badly, but the words must survive.
        val fragments = (0 until 12).map { i ->
            run("frag$i", 60f + (i % 3) * 180f, 700f - (i / 3) * 90f)
        }
        val result = pipeline.reflow(FakeSource(listOf(
            PdfPage(PageGeometry(0, 612f, 792f), fragments)
        )))
        val text = result.blocks.joinToString(" ") { it.plainText }
        (0 until 12).forEach { i ->
            assertTrue(text.contains("frag$i"), "frag$i was lost at low confidence")
        }
    }

    @Test
    fun `no words are lost reflowing the large book`() {
        PdfBoxTextSource(Fixtures.largeBook()).use { s ->
            val result = pipeline.reflow(s)
            assertTrue(result.blocks.size > 100, "expected many blocks, got ${result.blocks.size}")
            val text = result.blocks.joinToString(" ") { it.plainText }
            assertTrue(text.contains("Distributed systems are a collection"))
        }
    }
}
