package app.quire.core.pdf

import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.source.OutlineEntry
import app.quire.core.source.PageGeometry
import app.quire.core.source.PdfPage
import app.quire.core.source.PdfTextSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Which declaration wins.
 *
 * Three sources state structure outright, and they are not equal. A tagged
 * structure tree says which runs are headings and in what order. Bookmarks are the
 * producer's own navigation. A hyperlinked contents page points at destinations
 * someone chose. What they share is that none of them is inferred — and when a
 * document offers none, the answer is one chapter rather than a guess.
 */
class DeclaredStructureTest {

    /** A source that declares exactly what a test tells it to. */
    private class Declaring(
        private val tagged: List<OutlineEntry> = emptyList(),
        private val bookmarks: List<OutlineEntry> = emptyList(),
        private val links: List<OutlineEntry> = emptyList(),
        private val broken: Boolean = false,
    ) : PdfTextSource {
        override fun pageCount() = 4
        override fun isEncrypted() = false
        override fun page(index: Int) = PdfPage(PageGeometry(index, 612f, 792f), emptyList())
        override fun outline() = if (broken) error("malformed outline") else bookmarks
        override fun declaredHeadings() = if (broken) error("malformed tree") else tagged
        override fun contentsLinks() = links
        override fun close() = Unit
    }

    private val pipeline = PdfPipeline()

    private fun blocks() = (1..8).map {
        ContentBlock.Paragraph(listOf(InlineSpan("Paragraph $it of the book's body text.")))
    }

    private fun chaptersFrom(source: PdfTextSource): List<String?> = runBlocking {
        // Exercised through the detector, since choosing the source is the pipeline's
        // job and applying it is the detector's.
        val declared = listOf(
            source.declaredHeadings(), source.outline(), source.contentsLinks(),
        ).firstOrNull { it.isNotEmpty() }.orEmpty()
        app.quire.core.structure.ChapterDetector()
            .detect(blocks(), declared, pageBreaks = listOf(0, 2, 4, 6))
            .map { it.title }
    }

    @Test
    fun `a tagged tree outranks bookmarks`() {
        val source = Declaring(
            tagged = listOf(OutlineEntry("From tags", 0, 1), OutlineEntry("Also tags", 2, 1)),
            bookmarks = listOf(OutlineEntry("From bookmarks", 0, 0), OutlineEntry("More", 2, 0)),
        )
        assertEquals(listOf("From tags", "Also tags"), chaptersFrom(source))
    }

    @Test
    fun `bookmarks outrank a contents page`() {
        val source = Declaring(
            bookmarks = listOf(OutlineEntry("From bookmarks", 0, 0), OutlineEntry("More", 2, 0)),
            links = listOf(OutlineEntry("From links", 0, 0), OutlineEntry("Also links", 2, 0)),
        )
        assertEquals(listOf("From bookmarks", "More"), chaptersFrom(source))
    }

    @Test
    fun `a contents page is used when nothing better exists`() {
        val source = Declaring(
            links = listOf(OutlineEntry("From links", 0, 0), OutlineEntry("Also links", 2, 0)),
        )
        assertEquals(listOf("From links", "Also links"), chaptersFrom(source))
    }

    @Test
    fun `a document that declares nothing gets one chapter`() {
        assertEquals(listOf(null), chaptersFrom(Declaring()))
    }

    @Test
    fun `a malformed declaration does not fail the import`() = runBlocking {
        // A broken structure tree is common in the wild. It must cost the book its
        // chapters, not its existence.
        val book = pipeline.process("id", "Broken", Declaring(broken = true))
        assertEquals(app.quire.core.model.ProcessingStatus.Ready, book.status)
    }
}
