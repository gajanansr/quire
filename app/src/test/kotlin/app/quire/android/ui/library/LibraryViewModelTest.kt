package app.quire.android.ui.library

import app.quire.android.data.LibraryBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryStateTest {

    private fun book(
        id: String, progress: Double = 0.0, opened: Long? = null,
    ) = LibraryBook(
        id = id, title = "Title $id", author = "Author", coverPath = null,
        progress = progress, chapterCount = 3, lastOpenedAt = opened, reflowFailed = false,
    )

    @Test
    fun `a loading library is not treated as empty`() {
        // Otherwise the empty state flashes on every cold start.
        assertFalse(LibraryState(loading = true).isEmpty)
    }

    @Test
    fun `an empty loaded library is empty`() {
        assertTrue(LibraryState(books = emptyList(), loading = false).isEmpty)
    }

    @Test
    fun `continue reading offers the most recent started book`() {
        val state = LibraryState(
            books = listOf(
                book("a", progress = 0.42, opened = 3_000),
                book("b", progress = 0.10, opened = 2_000),
            ),
            loading = false,
        )
        assertEquals("a", state.continueReading?.id)
    }

    @Test
    fun `an unopened book is not offered as continue reading`() {
        val state = LibraryState(books = listOf(book("a")), loading = false)
        assertNull("a book never opened should not say Continue", state.continueReading)
    }

    @Test
    fun `a book at zero progress is not offered as continue reading`() {
        val state = LibraryState(
            books = listOf(book("a", progress = 0.0, opened = 1_000)), loading = false,
        )
        assertNull("nothing to continue from", state.continueReading)
    }

    @Test
    fun `a finished book is not offered as continue reading`() {
        val state = LibraryState(
            books = listOf(book("a", progress = 1.0, opened = 1_000)), loading = false,
        )
        assertNull("a finished book should not invite continuing", state.continueReading)
    }

    @Test
    fun `continue reading falls through to the next eligible book`() {
        val state = LibraryState(
            books = listOf(
                book("finished", progress = 1.0, opened = 9_000),
                book("started", progress = 0.3, opened = 8_000),
            ),
            loading = false,
        )
        assertEquals("started", state.continueReading?.id)
    }
}
