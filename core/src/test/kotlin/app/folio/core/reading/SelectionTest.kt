package app.folio.core.reading

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Selection arithmetic.
 *
 * A selection is stated in chapter coordinates and drawn on a page that shows only
 * part of each block, so every highlight goes through [Selection.portionOf]. When
 * that is wrong the highlight still appears — over the wrong words — which is the
 * kind of bug a screenshot makes look like a rendering glitch. These are the cases
 * the reader can actually produce.
 */
class SelectionTest {

    private fun anchor(block: Int, char: Int) = TextAnchor(block, char)

    // The block, its slice on this page, and a span: the three things that have to
    // line up. Written as a helper so each test reads as one sentence.
    private fun portion(span: TextSpan, block: Int, sliceStart: Int, sliceEnd: Int) =
        Selection.portionOf(span, block, sliceStart, sliceEnd)

    @Test
    fun `a span is normalised however the reader dragged`() {
        val forwards = TextSpan.of(anchor(1, 4), anchor(1, 20))
        val backwards = TextSpan.of(anchor(1, 20), anchor(1, 4))
        assertEquals(forwards, backwards, "dragging right to left produced a different span")
    }

    @Test
    fun `a span inside one block maps onto a whole slice`() {
        val span = TextSpan.of(anchor(2, 10), anchor(2, 25))
        assertEquals(10 until 25, portion(span, block = 2, sliceStart = 0, sliceEnd = 80))
    }

    @Test
    fun `a block strictly inside the span is selected end to end`() {
        // The middle of a three-block selection has no offset of its own: the whole
        // paragraph is in it, whatever part of it this page happens to draw.
        val span = TextSpan.of(anchor(1, 30), anchor(3, 5))
        assertEquals(0 until 60, portion(span, block = 2, sliceStart = 0, sliceEnd = 60))
    }

    @Test
    fun `a selection is re-based onto the slice the page actually draws`() {
        // The bug this exists to prevent. Pagination splits a long paragraph, so the
        // second page draws characters 100..180 of block 2 as a string whose own
        // index 0 is the chapter's character 100. A highlight of 120..140 has to come
        // out as 20..40, not 120..140 — which would land off the end or on the wrong
        // words entirely.
        val span = TextSpan.of(anchor(2, 120), anchor(2, 140))
        assertEquals(20 until 40, portion(span, block = 2, sliceStart = 100, sliceEnd = 180))
    }

    @Test
    fun `a selection that starts before the slice is clipped to it`() {
        val span = TextSpan.of(anchor(2, 40), anchor(2, 130))
        assertEquals(0 until 30, portion(span, block = 2, sliceStart = 100, sliceEnd = 180))
    }

    @Test
    fun `a selection that runs past the slice is clipped to it`() {
        val span = TextSpan.of(anchor(2, 150), anchor(4, 0))
        assertEquals(50 until 80, portion(span, block = 2, sliceStart = 100, sliceEnd = 180))
    }

    @Test
    fun `a block this page does not show is not selected`() {
        val span = TextSpan.of(anchor(2, 0), anchor(2, 50))
        assertNull(portion(span, block = 5, sliceStart = 0, sliceEnd = 80))
        assertNull(portion(span, block = 0, sliceStart = 0, sliceEnd = 80))
    }

    @Test
    fun `a slice on the far side of the selection is not selected`() {
        // The same block, a later page. Everything selected is behind this slice.
        val span = TextSpan.of(anchor(2, 10), anchor(2, 40))
        assertNull(portion(span, block = 2, sliceStart = 100, sliceEnd = 180))
    }

    @Test
    fun `an empty selection highlights nothing`() {
        val span = TextSpan.of(anchor(2, 30), anchor(2, 30))
        assertTrue(span.isEmpty)
        assertNull(portion(span, block = 2, sliceStart = 0, sliceEnd = 80))
    }

    @Test
    fun `a long press takes the word, not the character`() {
        val text = "Bagel and cream cheese, classic combo"
        assertEquals("cream", text.substring(WordBoundary.expand(text, 11)))
        // Anywhere in the word gives the same word.
        assertEquals("cream", text.substring(WordBoundary.expand(text, 14)))
    }

    @Test
    fun `an apostrophe stays inside its word`() {
        // Both kinds. A curly apostrophe is not a letter, and treating it as a break
        // makes two words out of one possessive.
        listOf("India's future", "India’s future").forEach { text ->
            assertEquals(text.take(7), text.substring(WordBoundary.expand(text, 2)))
        }
    }

    @Test
    fun `pressing the trailing edge of a word still takes that word`() {
        val text = "Bagel and cream"
        assertEquals("Bagel", text.substring(WordBoundary.expand(text, 5)))
    }

    @Test
    fun `pressing whitespace between words takes neither`() {
        // Better an empty selection the reader can retry than an arbitrary guess at
        // which neighbour they meant.
        val text = "Bagel  and"
        assertTrue(WordBoundary.expand(text, 6).isEmpty())
    }

    @Test
    fun `punctuation is not swept into the word`() {
        val text = "cream cheese, classic"
        assertEquals("cheese", text.substring(WordBoundary.expand(text, 7)))
    }

    @Test
    fun `selected text across blocks reads as paragraphs`() {
        val blocks = listOf(
            "Avinash, a batchmate of mine from IIMA.",
            "‘Bagel and cream cheese, classic combo,’ he said.",
            "‘Thanks, Avinash,’ I said.",
        )
        val span = TextSpan.of(anchor(0, 9), anchor(2, 18))

        assertEquals(
            "a batchmate of mine from IIMA.\n\n" +
                "‘Bagel and cream cheese, classic combo,’ he said.\n\n" +
                "‘Thanks, Avinash,’",
            Selection.textOf(blocks, span),
        )
    }

    @Test
    fun `selected text within one block is just that text`() {
        val blocks = listOf("Bagel and cream cheese, classic combo.")
        val span = TextSpan.of(anchor(0, 10), anchor(0, 22))
        assertEquals("cream cheese", Selection.textOf(blocks, span))
    }

    @Test
    fun `an out of range anchor cannot throw`() {
        // Blocks are re-derived when a book is reprocessed, and a stored highlight
        // can outlive the chapter it was made in. Losing the highlight is fine;
        // crashing the reader on open is not.
        val blocks = listOf("Only one block here.")
        val span = TextSpan.of(anchor(0, 5), anchor(9, 400))
        assertEquals("one block here.", Selection.textOf(blocks, span))
    }
}
