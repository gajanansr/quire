package app.folio.core.epub

import app.folio.core.fixtures.Fixtures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubContainerTest {

    @Test
    fun `resolves the OPF path from container xml`() {
        EpubContainer(Fixtures.cleanEpub()).use { c ->
            assertEquals("OEBPS/content.opf", c.opfPath())
        }
    }

    @Test
    fun `reads title and author from dublin core metadata`() {
        EpubContainer(Fixtures.cleanEpub()).use { c ->
            val (title, author) = c.titleAndAuthor()
            assertEquals("A History of Quiet Things", title)
            assertEquals("Ada Marlowe", author)
        }
    }

    @Test
    fun `reads language publisher and identifier`() {
        EpubContainer(Fixtures.cleanEpub()).use { c ->
            val m = c.metadata()
            assertEquals("en", m.language)
            assertEquals("Folio Test Press", m.publisher)
            assertEquals("urn:uuid:folio-test-0001", m.identifier)
        }
    }

    @Test
    fun `reads the description and subjects for Book Details`() {
        EpubContainer(Fixtures.cleanEpub()).use { c ->
            val m = c.metadata()
            assertEquals(
                "A study of the spaces between words, and what lives there.",
                m.description,
            )
            assertEquals(listOf("Essay", "Design"), m.subjects)
        }
    }

    @Test
    fun `a book without a description reports null rather than an empty string`() {
        EpubContainer(Fixtures.malformedEpub()).use { c ->
            assertNull(c.metadata().description)
        }
    }

    @Test
    fun `spine hrefs are in reading order and resolved against the OPF directory`() {
        EpubContainer(Fixtures.cleanEpub()).use { c ->
            assertEquals(listOf("OEBPS/c1.xhtml", "OEBPS/c2.xhtml"), c.spineHrefs())
        }
    }

    @Test
    fun `nav titles map resolved hrefs to chapter titles`() {
        EpubContainer(Fixtures.cleanEpub()).use { c ->
            val nav = c.navTitles()
            assertEquals("The Weight of Silence", nav["OEBPS/c1.xhtml"])
            assertEquals("What the River Kept", nav["OEBPS/c2.xhtml"])
        }
    }

    @Test
    fun `readEntry returns bytes for a spine document`() {
        EpubContainer(Fixtures.cleanEpub()).use { c ->
            val bytes = c.readEntry("OEBPS/c1.xhtml")
            assertNotNull(bytes)
            assertTrue(String(bytes).contains("The Weight of Silence"))
        }
    }

    @Test
    fun `readEntry returns null for a missing entry rather than throwing`() {
        EpubContainer(Fixtures.cleanEpub()).use { c ->
            assertNull(c.readEntry("OEBPS/nope.xhtml"))
        }
    }

    @Test
    fun `epub without a nav document still yields a spine, with no nav titles`() {
        EpubContainer(Fixtures.epubNoNav()).use { c ->
            assertEquals(listOf("OEBPS/c1.xhtml", "OEBPS/c2.xhtml"), c.spineHrefs())
            assertTrue(c.navTitles().isEmpty())
        }
    }

    @Test
    fun `malformed epub returns null opf path rather than throwing`() {
        EpubContainer(Fixtures.malformedEpub()).use { c ->
            assertNull(c.opfPath())
            assertTrue(c.spineHrefs().isEmpty())
        }
    }

    @Test
    fun `a file that is not a zip at all fails cleanly on open`() {
        val notAZip = Fixtures.unsupportedFile()   // a PNG named .epub
        val opened = runCatching { EpubContainer(notAZip).use { it.opfPath() } }
        assertTrue(
            opened.isFailure || opened.getOrNull() == null,
            "expected a clean failure or null, got ${opened.getOrNull()}",
        )
    }
}
