package app.folio.android.ui.details

import app.folio.android.data.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDetailsStateTest {

    private fun entity(
        totalChars: Int = 400_000,
        chapters: Int = 12,
        subjects: String = "Essay|Design",
        description: String? = "A study of the spaces between words.",
        format: String = "EPUB",
        reflowFailed: Boolean = false,
    ) = BookEntity(
        id = "b1", title = "A History of Quiet Things", author = "Ada Marlowe",
        coverPath = null, sourceFormat = format, language = "en", publisher = null,
        identifier = null, subjects = subjects, description = description,
        totalChars = totalChars, chapterCount = chapters, reflowFailed = reflowFailed,
        addedAt = 1L, lastOpenedAt = null,
    )

    private fun state(
        entity: BookEntity = entity(),
        progress: Double = 0.42,
        chapterIndex: Int = 3,
        chapterTitle: String? = "Distributed Systems",
        goal: Int = 20,
    ) = BookDetailsState.from(entity, progress, chapterIndex, chapterTitle, goal)

    @Test
    fun `percent complete rounds to a whole number`() {
        assertEquals(42, state(progress = 0.42).percentComplete)
        assertEquals(100, state(progress = 1.0).percentComplete)
        assertEquals(0, state(progress = 0.0).percentComplete)
    }

    @Test
    fun `chapter label is one-based for display`() {
        // Index 3 is the fourth chapter; "Ch. 3" would be off by one to a reader.
        assertEquals("Ch. 4", state(chapterIndex = 3).chapterLabel)
        assertEquals("Ch. 1", state(chapterIndex = 0).chapterLabel)
    }

    @Test
    fun `a book with no chapters shows a dash rather than Ch 1 of nothing`() {
        assertEquals("—", state(entity = entity(chapters = 0)).chapterLabel)
    }

    @Test
    fun `pages read as current of total`() {
        val label = state(progress = 0.5).pagesLabel
        assertTrue("unexpected pages label: $label", label.matches(Regex("\\d+/\\d+")))
    }

    @Test
    fun `an empty book shows dashes rather than zeroes`() {
        val s = state(entity = entity(totalChars = 0, chapters = 0))
        assertEquals("—", s.pagesLabel)
        assertEquals("—", s.chapterLabel)
    }

    @Test
    fun `a finished book says Done rather than 0m left`() {
        assertEquals("Done", state(progress = 1.0).timeLeftLabel)
    }

    @Test
    fun `the pace sentence disappears once the book is finished`() {
        assertTrue(state(progress = 0.1).paceSentence != null)
        assertNull(state(progress = 1.0).paceSentence)
    }

    @Test
    fun `subjects decode into genre chips`() {
        assertEquals(listOf("Essay", "Design"), state().subjects)
    }

    @Test
    fun `a book with no subjects shows no chips, not one blank chip`() {
        assertTrue(state(entity = entity(subjects = "")).subjects.isEmpty())
    }

    @Test
    fun `a book with no description has no synopsis rather than an invented one`() {
        assertNull(state(entity = entity(description = null)).synopsis)
        assertNull(state(entity = entity(description = "   ")).synopsis)
    }

    @Test
    fun `the format never exposes the OCR path to the reader`() {
        // Whether a scan needed recognising is Folio's business, not the reader's.
        assertEquals("PDF", BookDetailsState.formatLabel("PDF_OCR"))
        assertEquals("PDF", BookDetailsState.formatLabel("PDF_TEXT"))
        assertEquals("Text", BookDetailsState.formatLabel("TXT"))
        assertFalse(BookDetailsState.formatLabel("PDF_OCR").contains("_"))
    }

    @Test
    fun `an unknown format degrades to a neutral word`() {
        assertEquals("Book", BookDetailsState.formatLabel("SOMETHING_NEW"))
    }

    @Test
    fun `a reflow-failed book is flagged so the original can be offered`() {
        assertTrue(state(entity = entity(reflowFailed = true)).reflowFailed)
    }

    @Test
    fun `started is false until there is real progress`() {
        assertFalse(state(progress = 0.0).started)
        assertTrue(state(progress = 0.01).started)
    }

    @Test
    fun `the three tabs carry the handoff's labels`() {
        assertEquals(listOf("Synopsis", "Details", "Author"), DetailsTab.entries.map { it.label })
    }
}
