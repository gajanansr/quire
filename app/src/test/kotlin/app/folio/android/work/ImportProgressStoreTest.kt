package app.folio.android.work

import app.folio.android.data.BookStore
import app.folio.core.model.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImportProgressStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store() = ImportProgressStore(BookStore(temp.root))

    private fun state(id: String, stage: String, pages: Int = 0) = ImportState(
        bookId = id, stage = stage, sourceUri = "content://x/$id",
        pagesDone = pages, pagesTotal = 100, updatedAt = 1L,
    )

    @Test
    fun `state round trips`() {
        val s = store()
        s.save(state("a", ImportProgressStore.STAGE_OCR, pages = 37))
        val loaded = s.load("a")
        assertEquals(ImportProgressStore.STAGE_OCR, loaded?.stage)
        assertEquals(37, loaded?.pagesDone)
    }

    @Test
    fun `loading an unknown book returns null`() {
        assertNull(store().load("nope"))
    }

    @Test
    fun `a truncated state file reads as absent rather than throwing`() {
        val s = store()
        s.save(state("a", ImportProgressStore.STAGE_OCR))
        File(BookStore(temp.root).bookDir("a"), "processing.json").writeText("{ trunc")
        assertNull(s.load("a"))
    }

    @Test
    fun `no temp file is left behind after a save`() {
        val s = store()
        s.save(state("a", ImportProgressStore.STAGE_EXTRACTING))
        val leftovers = BookStore(temp.root).bookDir("a")
            .listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue("temp file survived: $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `clear removes the state`() {
        val s = store()
        s.save(state("a", ImportProgressStore.STAGE_OCR))
        s.clear("a")
        assertNull(s.load("a"))
    }

    @Test
    fun `unfinished lists imports that were mid-flight`() {
        val s = store()
        s.save(state("mid", ImportProgressStore.STAGE_OCR))
        s.save(state("done", ImportProgressStore.STAGE_READY))
        s.save(state("broke", ImportProgressStore.STAGE_FAILED).copy(failure = FailureReason.CORRUPT_FILE))

        assertEquals(listOf("mid"), s.unfinished().map { it.bookId })
    }

    @Test
    fun `unfinished is empty when nothing is in flight`() {
        assertTrue(store().unfinished().isEmpty())
    }

    @Test
    fun `an ocr import records how far it got`() {
        val s = store()
        s.save(state("a", ImportProgressStore.STAGE_OCR, pages = 240))
        // Resuming a 400-page scan must not redo 240 pages of recognition.
        assertEquals(240, s.unfinished().single().pagesDone)
    }
}
