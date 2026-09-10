package app.folio.android.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The swatch is derived rather than stored, so the derivation is the contract.
 */
class CoverGradientTest {

    @Test
    fun `the same book always gets the same swatch`() {
        val id = "6f1c0a2e-1f8e-4c3a-9a11-0d2b4c5e6f70"
        assertEquals(CoverGradient.indexOf(id), CoverGradient.indexOf(id))
    }

    @Test
    fun `the index stays in range, including for negative hashes`() {
        // String.hashCode is often negative; a plain remainder would give a negative
        // index and crash the first time such a book was shown.
        listOf("a", "zzzz", "polygenelubricants", " ", "-1", "book-42", "")
            .forEach { id ->
                val i = CoverGradient.indexOf(id)
                assertTrue("index $i out of range for '$id'", i in 0 until CoverGradient.size)
            }
    }

    @Test
    fun `a hash with the sign bit set still lands in range`() {
        // "polygenelubricants" hashes to Integer.MIN_VALUE, where negation overflows
        // and abs() returns a negative number. The mask avoids that trap entirely.
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())
        val i = CoverGradient.indexOf("polygenelubricants")
        assertTrue("index $i out of range", i in 0 until CoverGradient.size)
    }

    @Test
    fun `different books generally get different swatches`() {
        val indices = (1..30).map { CoverGradient.indexOf("book-$it") }.toSet()
        assertTrue("expected a spread of swatches, got $indices", indices.size >= 3)
    }
}
