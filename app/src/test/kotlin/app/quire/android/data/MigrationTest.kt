package app.quire.android.data

import app.quire.android.data.BookRepository.Companion.subjectsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectEncodingTest {

    @Test
    fun `subjects round trip through the separator`() {
        val subjects = listOf("Essay", "Design", "Systems")
        val stored = subjects.joinToString(BookRepository.SUBJECT_SEPARATOR)
        assertEquals(subjects, subjectsOf(stored))
    }

    @Test
    fun `an empty stored value yields no subjects, not one blank chip`() {
        // "".split("|") returns [""] — a book with no genres would otherwise
        // render a single empty chip.
        assertTrue(subjectsOf("").isEmpty())
    }

    @Test
    fun `blank entries are discarded`() {
        assertEquals(listOf("Essay"), subjectsOf("Essay||  |"))
    }

    @Test
    fun `whitespace around a subject is trimmed`() {
        assertEquals(listOf("Essay", "Design"), subjectsOf(" Essay | Design "))
    }
}
