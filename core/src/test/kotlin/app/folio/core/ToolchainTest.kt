package app.folio.core

import org.apache.pdfbox.pdmodel.PDDocument
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolchainTest {

    @Test
    fun `runs on JDK 21 or newer`() {
        val major = Runtime.version().feature()
        assertTrue(major >= 21, "expected JDK 21+, got $major")
    }

    @Test
    fun `apache pdfbox 2x is on the test classpath`() {
        // PDFBox 3.x moved loading to a separate Loader class; 2.x keeps
        // PDDocument.load. If the wrong major is pinned this stops compiling.
        val doc = PDDocument()
        assertEquals(0, doc.numberOfPages)
        doc.close()
    }
}
