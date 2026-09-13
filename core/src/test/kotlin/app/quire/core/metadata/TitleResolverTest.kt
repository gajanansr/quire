package app.quire.core.metadata

import app.quire.core.source.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Deciding what a PDF is called.
 *
 * PDFs arrive with a filename for a title because nothing ever read the document's
 * own metadata. Reading it is not enough on its own: a PDF's Title field is wrong
 * often enough that trusting it blindly would replace one bad title with another —
 * `Microsoft Word - thesis_final_v3.doc` is a real and common value.
 *
 * So each source is tried in order and validated before it is believed, and the
 * filename remains the floor. The point is never to invent a title, only to prefer
 * a better one when the file actually carries it.
 */
class TitleResolverTest {

    private fun run(text: String, size: Float, y: Float, bold: Boolean = false) =
        TextRun(text, x = 72f, y = y, width = 400f, height = size, fontSize = size,
            fontName = "Serif", bold = bold, italic = false)

    /** A plausible title page: one big line, a byline, then body copy. */
    private val titlePage = listOf(
        run("The Weight of Silence", size = 28f, y = 700f, bold = true),
        run("Ada Marlowe", size = 14f, y = 650f),
        run("Chapter One", size = 11f, y = 600f),
        run("Distributed systems are a collection of independent computers.", 11f, 580f),
    )

    @Test
    fun `a good document title is used`() {
        val r = TitleResolver.resolve(
            filename = "scan_0001",
            info = DocumentInfo(title = "The Weight of Silence", author = "Ada Marlowe"),
        )
        assertEquals("The Weight of Silence", r.title)
        assertEquals("Ada Marlowe", r.author)
    }

    @Test
    fun `a word processor's leftovers are not a title`() {
        // The single commonest junk value in the wild.
        val r = TitleResolver.resolve(
            filename = "quiet-things",
            info = DocumentInfo(title = "Microsoft Word - thesis_final_v3.doc", author = null),
        )
        assertEquals("quiet-things", r.title)
    }

    @Test
    fun `a title that is really a filename is refused`() {
        listOf("chapter1.tex", "book.indd", "output.pdf", "Untitled", "Document1", "  ")
            .forEach { junk ->
                val r = TitleResolver.resolve("quiet-things", DocumentInfo(junk, null))
                assertEquals("quiet-things", r.title, "accepted junk title: $junk")
            }
    }

    @Test
    fun `a title identical to the filename adds nothing`() {
        val r = TitleResolver.resolve("quiet-things", DocumentInfo("quiet-things", null))
        assertEquals("quiet-things", r.title)
    }

    @Test
    fun `the biggest type on page one is the title when metadata fails`() {
        // Title pages set the title largest. TextRun already carries font size, so
        // this signal costs nothing to read.
        val r = TitleResolver.resolve(
            filename = "scan_0001",
            info = DocumentInfo("Microsoft Word - x.doc", null),
            pageOne = titlePage,
        )
        assertEquals("The Weight of Silence", r.title)
    }

    @Test
    fun `body text is never mistaken for a title`() {
        // A scan with no title page: every run is the same size, so there is no
        // "biggest" line and the heuristic must decline rather than grab a sentence.
        val uniform = List(8) { run("Distributed systems are a collection of computers.", 11f, 700f - it * 20) }
        val r = TitleResolver.resolve("scan_0001", info = null, pageOne = uniform)
        assertEquals("scan_0001", r.title)
    }

    @Test
    fun `an implausibly long line is not a title`() {
        val longLine = listOf(run("x".repeat(300), size = 28f, y = 700f))
        val r = TitleResolver.resolve("scan_0001", info = null, pageOne = longLine)
        assertEquals("scan_0001", r.title)
    }

    @Test
    fun `an author is taken from a filename that names one`() {
        val r = TitleResolver.resolve("Ada Marlowe - The Weight of Silence", info = null)
        assertEquals("The Weight of Silence", r.title)
        assertEquals("Ada Marlowe", r.author)
    }

    @Test
    fun `a hyphenated title keeps its hyphen and gains no author`() {
        // "quiet-things" and "A Study - Of Sorts" must not be torn into an author.
        val r = TitleResolver.resolve("the weight - of silence", info = null)
        assertEquals("the weight - of silence", r.title)
        assertNull(r.author)
    }

    @Test
    fun `a tool name is not an author`() {
        val r = TitleResolver.resolve("book", DocumentInfo("A Real Title", "Acrobat PDFMaker 11"))
        assertEquals("A Real Title", r.title)
        assertNull(r.author)
    }

    @Test
    fun `nothing anywhere still yields the filename`() {
        val r = TitleResolver.resolve("scan_0001", info = null)
        assertEquals("scan_0001", r.title)
        assertNull(r.author)
    }

    @Test
    fun `a document author survives even when its title does not`() {
        val r = TitleResolver.resolve(
            filename = "scan_0001",
            info = DocumentInfo(title = "Untitled", author = "Ada Marlowe"),
        )
        assertEquals("scan_0001", r.title)
        assertEquals("Ada Marlowe", r.author)
    }

    @Test
    fun `a title page carrying only a title is still a title page`() {
        // With one line there are no neighbours to be larger than: the page's median
        // size is the title's own. Judged on absolute size instead.
        val bare = listOf(run("The Weight of Silence", size = 28f, y = 640f))
        val r = TitleResolver.resolve("scan_0001", info = null, pageOne = bare)
        assertEquals("The Weight of Silence", r.title)
    }

    @Test
    fun `a sparse page of ordinary type is not a title page`() {
        // Sparse alone is not enough, or the first three lines of any scanned page
        // would become its title.
        val sparse = List(3) { run("Distributed systems are a collection.", 11f, 700f - it * 20) }
        val r = TitleResolver.resolve("scan_0001", info = null, pageOne = sparse)
        assertEquals("scan_0001", r.title)
    }

    // ------------------------------------------------- marks of where a file came from

    @Test
    fun `an aggregator's stamp is trimmed, not the title with it`() {
        // A real book, from a real file. The name is right there next to the stamp,
        // and rejecting the whole title would fall back to a filename carrying the
        // same stamp.
        mapOf(
            "One Indian Girl - PDFDrive.com" to "One Indian Girl",
            "Dracula (z-lib.org)" to "Dracula",
            "[www.example.net] The Odyssey" to "The Odyssey",
            "The Odyssey_bookfi.org" to "The Odyssey",
            "www.pdfdrive.com - Sapiens" to "Sapiens",
        ).forEach { (stamped, clean) ->
            assertEquals(clean, TitleResolver.resolve("file", DocumentInfo(stamped, null)).title)
        }
    }

    @Test
    fun `a title that merely contains a dot or a dash keeps it`() {
        // The trim must not eat punctuation a book actually uses.
        listOf(
            "Dr. Jekyll and Mr. Hyde",
            "The Sound and the Fury - A Novel",
            "2001: A Space Odyssey",
            "Star.Crossed",
        ).forEach {
            assertEquals(it, TitleResolver.resolve("file", DocumentInfo(it, null)).title)
        }
    }
}