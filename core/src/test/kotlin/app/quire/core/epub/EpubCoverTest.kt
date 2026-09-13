package app.quire.core.epub

import app.quire.core.fixtures.Fixtures
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Finding an EPUB's cover.
 *
 * There is no single way a book declares one. EPUB 3 marks a manifest item with
 * `properties="cover-image"`; EPUB 2 has no such property and instead points at a
 * manifest id from a `<meta name="cover">`. Retailers asked publishers to do both,
 * so plenty of books carry each, some carry neither, and a good many carry an item
 * called "cover" that is the cover *page* rather than the cover image.
 *
 * Every rule here comes from a real shape a book takes. The last test is the one
 * that matters most: a book with no cover keeps no cover, because a wrong cover is
 * worse than the gradient the design already falls back to.
 */
class EpubCoverTest {

    private fun opf(manifest: String, metadata: String = "") = Jsoup.parse(
        """
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata>$metadata</metadata>
          <manifest>$manifest</manifest>
          <spine><itemref idref="c1"/></spine>
        </package>
        """.trimIndent(),
        "", Parser.xmlParser(),
    )

    @Test
    fun `epub 3 declares the cover as a manifest property`() {
        val doc = opf(
            """<item id="ci" properties="cover-image" href="images/cover.jpg"
                 media-type="image/jpeg"/>"""
        )
        assertEquals("images/cover.jpg", EpubCover.hrefIn(doc))
    }

    @Test
    fun `epub 2 points at a manifest id from the metadata`() {
        val doc = opf(
            manifest = """<item id="cov" href="cover.png" media-type="image/png"/>""",
            metadata = """<meta name="cover" content="cov"/>""",
        )
        assertEquals("cover.png", EpubCover.hrefIn(doc))
    }

    @Test
    fun `the epub 3 property wins when a book declares both`() {
        val doc = opf(
            manifest = """
                <item id="old" href="legacy.png" media-type="image/png"/>
                <item id="ci" properties="cover-image" href="real.jpg" media-type="image/jpeg"/>
            """.trimIndent(),
            metadata = """<meta name="cover" content="old"/>""",
        )
        assertEquals("real.jpg", EpubCover.hrefIn(doc))
    }

    @Test
    fun `a meta pointing at nothing falls through instead of failing`() {
        val doc = opf(
            manifest = """<item id="img" href="art/front.jpeg" media-type="image/jpeg"/>""",
            metadata = """<meta name="cover" content="missing-id"/>""",
        )
        // Nothing names a cover, and no item is called one, so there is none.
        assertNull(EpubCover.hrefIn(doc))
    }

    @Test
    fun `an item named cover is used when nothing is declared`() {
        val doc = opf("""<item id="cover" href="c.jpg" media-type="image/jpeg"/>""")
        assertEquals("c.jpg", EpubCover.hrefIn(doc))
    }

    @Test
    fun `an href that looks like a cover is used as a last resort`() {
        val doc = opf("""<item id="x7" href="images/Cover.jpeg" media-type="image/jpeg"/>""")
        assertEquals("images/Cover.jpeg", EpubCover.hrefIn(doc))
    }

    @Test
    fun `the cover page is not mistaken for the cover image`() {
        // The commonest false positive: an item with id "cover" that is the XHTML
        // page showing the cover. Returning it would store markup as an image.
        val doc = opf(
            """
            <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
            <item id="cimg" href="cover.jpeg" media-type="image/jpeg"/>
            """.trimIndent()
        )
        assertEquals("cover.jpeg", EpubCover.hrefIn(doc))
    }

    @Test
    fun `a declared cover that is not an image is refused`() {
        val doc = opf(
            manifest = """<item id="cov" href="cover.xhtml"
                            media-type="application/xhtml+xml"/>""",
            metadata = """<meta name="cover" content="cov"/>""",
        )
        assertNull(EpubCover.hrefIn(doc))
    }

    @Test
    fun `a book with no cover keeps no cover`() {
        val doc = opf("""<item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>""")
        assertNull(EpubCover.hrefIn(doc))
    }

    @Test
    fun `an unreadable package yields nothing rather than throwing`() {
        assertNull(EpubCover.hrefIn(Jsoup.parse("<not-a-package/>", "", Parser.xmlParser())))
    }
}

/** The same rules against real books, read out of a real zip. */
class EpubContainerCoverTest {

    @Test
    fun `a book that ships a cover gives back decodable image bytes`() {
        EpubContainer(Fixtures.cleanEpub()).use { c ->
            val bytes = c.coverImage()
            assertNotNull(bytes, "the fixture declares a cover")
            // PNG magic: proof it is an image rather than markup or a stray entry.
            assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), bytes.take(4).map { it.toInt() and 0xFF })
        }
    }

    @Test
    fun `a book without one gives back nothing`() {
        EpubContainer(Fixtures.epubNoNav()).use { c ->
            assertNull(c.coverImage())
        }
    }
}
