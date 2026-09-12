package app.folio.core.structure

import app.folio.core.fixtures.Fixtures
import app.folio.core.fixtures.PdfBoxTextSource
import app.folio.core.model.ContentBlock
import app.folio.core.model.InlineSpan
import app.folio.core.reflow.ReflowPipeline
import app.folio.core.source.OutlineEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterDetectorTest {

    private val detector = ChapterDetector()

    private fun para(s: String) = ContentBlock.Paragraph(listOf(InlineSpan(s)))
    private fun head(s: String, level: Int = 1) = ContentBlock.Heading(level, listOf(InlineSpan(s)))

    @Test
    fun `the pdf outline wins when present`() {
        val blocks = listOf(para("one"), para("two"), para("three"), para("four"))
        val chapters = detector.detect(
            blocks,
            outline = listOf(OutlineEntry("Opening", 0, 0), OutlineEntry("Closing", 1, 0)),
            pageBreaks = listOf(0, 2),
        )
        assertEquals(listOf("Opening", "Closing"), chapters.map { it.title })
        assertEquals(2, chapters[0].blocks.size)
        assertEquals(2, chapters[1].blocks.size)
    }

    // -------------------------------------------------- what is no longer guessed

    @Test
    fun `large type is not a chapter boundary`() {
        // It used to be. Two headings past a 0.6 score carved up a book, which is
        // how a dedication, a running head and a pull quote became chapters. Nothing
        // in a PDF says a large line is a heading — inferring it is inventing it.
        val blocks = listOf(
            head("The Weight of Silence"), para("body one"), para("body two"),
            head("What the River Kept"), para("body three"),
        )
        val chapters = detector.detect(blocks)
        assertEquals(1, chapters.size, "large type invented a boundary")
        assertEquals(5, chapters.single().blocks.size, "and lost blocks doing it")
    }

    @Test
    fun `the words Chapter One are not a chapter boundary`() {
        // The most tempting pattern of all, and still only a guess: books quote the
        // word, indexes list it, and front matter repeats it.
        val blocks = listOf(
            para("Chapter 1"), para("body one"),
            para("Chapter 2"), para("body two"),
        )
        assertEquals(1, detector.detect(blocks).size)
    }

    @Test
    fun `roman numerals are not a chapter boundary`() {
        val blocks = listOf(
            para("Part I"), para("body one"),
            para("Part II"), para("body two"),
            para("Part III"), para("body three"),
        )
        assertEquals(1, detector.detect(blocks).size)
    }

    @Test
    fun `nothing declared means nothing is lost`() {
        // The trade this policy makes. One plain chapter, every block present, in
        // order — rather than several chapters and a boundary nobody wrote.
        val blocks = listOf(
            head("The Weight of Silence"), para("body one"),
            head("What the River Kept"), para("body two"),
        )
        val chapter = detector.detect(blocks).single()
        assertEquals(blocks.size, chapter.blocks.size)
        assertEquals(blocks, chapter.blocks)
    }

    @Test
    fun `books without any chapter signal become one chapter, not invented structure`() {
        val blocks = (1..10).map { para("Just prose paragraph number $it with no structure at all.") }
        val chapters = detector.detect(blocks)
        assertEquals(1, chapters.size)
        assertEquals(10, chapters.single().blocks.size)
        assertEquals(null, chapters.single().title)
    }

    @Test
    fun `a lone heading does not fragment the book`() {
        // One heading in a long book is a section marker, not a chapter scheme.
        val blocks = listOf(head("A Note on Sources")) +
            (1..20).map { para("Body paragraph $it carrying the actual text of the book.") }
        val chapters = detector.detect(blocks)
        assertEquals(1, chapters.size, "a single heading should not define a chapter scheme")
    }

    @Test
    fun `char offsets are cumulative and cover every block`() {
        val blocks = listOf(head("One"), para("aaaa"), head("Two"), para("bbbbbb"))
        val chapters = detector.detect(blocks)
        var running = 0
        chapters.forEach { c ->
            assertEquals(running, c.startCharOffset)
            running += c.charCount
        }
        assertEquals(blocks.sumOf { b ->
            when (b) {
                is ContentBlock.Paragraph -> b.spans.sumOf { it.text.length }
                is ContentBlock.Heading -> b.spans.sumOf { it.text.length }
                else -> 0
            }
        }, running)
    }

    @Test
    fun `no blocks are lost across chapter splitting`() {
        val blocks = listOf(
            head("One"), para("a"), para("b"),
            head("Two"), para("c"),
            head("Three"), para("d"), para("e"),
        )
        val chapters = detector.detect(blocks)
        assertEquals(blocks.size, chapters.sumOf { it.blocks.size }, "blocks lost or duplicated")
    }

    @Test
    fun `empty input yields no chapters`() {
        assertTrue(detector.detect(emptyList()).isEmpty())
    }

    @Test
    fun `a book that declares its chapters gets exactly those chapters`() {
        PdfBoxTextSource(Fixtures.outlinedPdf()).use { s ->
            val reflow = ReflowPipeline().reflow(s)
            val chapters = detector.detect(reflow.blocks, s.outline(), reflow.pageBreaks)
            assertEquals(3, chapters.size, "titles were: ${chapters.map { it.title }}")
            assertTrue(chapters[0].title?.contains("Weight of Silence") == true,
                "unexpected first title: ${chapters[0].title}")
        }
    }

    @Test
    fun `a book that only looks chaptered gets one chapter`() {
        // The same three chapters, laid out identically, declaring nothing. A human
        // reads it as chaptered; the file does not say so, and Folio no longer
        // pretends to know.
        PdfBoxTextSource(Fixtures.chapteredPdf()).use { s ->
            val reflow = ReflowPipeline().reflow(s)
            val chapters = detector.detect(reflow.blocks, s.outline(), reflow.pageBreaks)
            assertEquals(1, chapters.size, "invented: ${chapters.map { it.title }}")
        }
    }
}
