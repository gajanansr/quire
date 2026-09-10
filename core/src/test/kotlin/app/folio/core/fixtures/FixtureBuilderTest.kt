package app.folio.core.fixtures

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class FixtureBuilderTest {

    @Test
    fun `generates every fixture and each is non-empty`() {
        val files = listOf(
            Fixtures.cleanEpub(), Fixtures.malformedEpub(), Fixtures.epubNoNav(),
            Fixtures.plainTxt(), Fixtures.singleColumnPdf(), Fixtures.twoColumnPdf(),
            Fixtures.headerFooterPdf(), Fixtures.chapteredPdf(), Fixtures.imageOnlyPdf(),
            Fixtures.largeBook(), Fixtures.corruptPdf(), Fixtures.unsupportedFile(),
        )
        files.forEach { f ->
            assertTrue(f.exists(), "${f.name} was not created")
            assertTrue(f.length() > 0, "${f.name} is empty")
        }
    }

    @Test
    fun `image only pdf has no extractable text layer`() {
        PDDocument.load(Fixtures.imageOnlyPdf()).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.trim().length < 20, "expected no text layer, got ${text.length} chars")
        }
    }

    @Test
    fun `header footer pdf repeats its running header on every page`() {
        PDDocument.load(Fixtures.headerFooterPdf()).use { doc ->
            val stripper = PDFTextStripper()
            val occurrences = (1..doc.numberOfPages).count { p ->
                stripper.startPage = p
                stripper.endPage = p
                stripper.getText(doc).contains("A HISTORY OF QUIET THINGS")
            }
            assertTrue(occurrences >= 5, "running header appeared on only $occurrences pages")
        }
    }

    @Test
    fun `single column pdf does have an extractable text layer`() {
        PDDocument.load(Fixtures.singleColumnPdf()).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.length > 500, "expected real text, got ${text.length} chars")
        }
    }

    @Test
    fun `large book has enough pages to stress pagination`() {
        PDDocument.load(Fixtures.largeBook()).use { doc ->
            assertTrue(doc.numberOfPages >= 400, "only ${doc.numberOfPages} pages")
        }
    }
}
