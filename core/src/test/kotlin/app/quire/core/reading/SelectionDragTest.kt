package app.quire.core.reading

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Moving a selection after it exists.
 *
 * The old model could only grow one end: the long press pinned the anchor and the
 * far end followed the finger, so a passage could never be extended backwards from
 * its start and, once the finger lifted, could not be adjusted at all. These are the
 * two motions that replace it — a sweep while the press is still held, which takes
 * whole words, and a handle drag afterwards, which takes single characters.
 *
 * Every case here is one a thumb produces in the first minute of use.
 */
class SelectionDragTest {

    private fun anchor(block: Int, char: Int) = TextAnchor(block, char)

    // "Bagel and cream cheese, classic combo." with a second paragraph after it.
    private val blocks = listOf(
        "Bagel and cream cheese, classic combo.",
        "Thanks, Avinash, I said.",
    )

    // ------------------------------------------------------- handle drags

    @Test
    fun `dragging the end handle leaves the start where it was`() {
        val span = TextSpan.of(anchor(0, 10), anchor(0, 15))
        val moved = Selection.movingEdge(span, SelectionEdge.END, anchor(0, 22))

        assertEquals(anchor(0, 10), moved.span.start, "the anchored start moved")
        assertEquals(anchor(0, 22), moved.span.end)
        assertEquals(SelectionEdge.END, moved.edge)
    }

    @Test
    fun `dragging the start handle leaves the end where it was`() {
        // The motion the old model could not express at all: the anchor was always
        // the long-press point, so a selection could never grow backwards.
        val span = TextSpan.of(anchor(0, 10), anchor(0, 15))
        val moved = Selection.movingEdge(span, SelectionEdge.START, anchor(0, 0))

        assertEquals(anchor(0, 0), moved.span.start)
        assertEquals(anchor(0, 15), moved.span.end, "the anchored end moved")
        assertEquals(SelectionEdge.START, moved.edge)
    }

    @Test
    fun `a handle dragged past the other one swaps roles rather than stopping`() {
        // Compose's own handles do this. Stopping dead at the crossing point feels
        // like the app has seized, and the finger is already past it by then.
        val span = TextSpan.of(anchor(0, 10), anchor(0, 15))
        val moved = Selection.movingEdge(span, SelectionEdge.END, anchor(0, 4))

        assertEquals(TextSpan.of(anchor(0, 4), anchor(0, 10)), moved.span)
        assertEquals(
            SelectionEdge.START, moved.edge,
            "after crossing, the held handle is the start and the anchor is the end",
        )
    }

    @Test
    fun `a drag onto the anchor is refused rather than collapsing the selection`() {
        // An empty span means hasSelection is false, which takes the handles and the
        // action bar off screen with the finger still down — the selection appears
        // to have been destroyed by a movement that was meant to shrink it.
        val span = TextSpan.of(anchor(0, 10), anchor(0, 15))
        val moved = Selection.movingEdge(span, SelectionEdge.END, anchor(0, 10))

        assertEquals(span, moved.span, "the selection collapsed to nothing")
        assertEquals(SelectionEdge.END, moved.edge)
    }

    @Test
    fun `a handle can be dragged into another block`() {
        val span = TextSpan.of(anchor(0, 10), anchor(0, 15))
        val moved = Selection.movingEdge(span, SelectionEdge.END, anchor(1, 7))

        assertEquals(TextSpan.of(anchor(0, 10), anchor(1, 7)), moved.span)
        assertEquals(SelectionEdge.END, moved.edge)
    }

    // ------------------------------------------------------- word sweeping

    @Test
    fun `a sweep forward takes whole words`() {
        // Touching the first letter of a word takes all of it. Character-accurate
        // sweeping reads as jitter: the passage grows and shrinks by a letter as the
        // finger shakes, and nobody is aiming at a letter with a moving thumb.
        val origin = TextSpan.of(anchor(0, 10), anchor(0, 15)) // "cream"
        val swept = Selection.sweptTo(blocks, origin, anchor(0, 17))

        assertEquals("cream cheese", blocks[0].substring(10, swept.end.charOffset))
    }

    @Test
    fun `a sweep backward keeps the word the press landed on`() {
        // The old model dropped it: the anchor was the pressed word's *start*, so
        // dragging left selected up to that start and the pressed word vanished.
        val origin = TextSpan.of(anchor(0, 10), anchor(0, 15)) // "cream"
        val swept = Selection.sweptTo(blocks, origin, anchor(0, 7))

        assertEquals(
            "and cream",
            blocks[0].substring(swept.start.charOffset, swept.end.charOffset),
        )
    }

    @Test
    fun `a sweep that stops in whitespace does not swallow the next word`() {
        val origin = TextSpan.of(anchor(0, 10), anchor(0, 15)) // "cream"
        val swept = Selection.sweptTo(blocks, origin, anchor(0, 15))

        assertEquals("cream", blocks[0].substring(10, swept.end.charOffset))
    }

    @Test
    fun `a sweep into the next block keeps the pressed word`() {
        val origin = TextSpan.of(anchor(0, 10), anchor(0, 15))
        val swept = Selection.sweptTo(blocks, origin, anchor(1, 3))

        assertEquals(anchor(0, 10), swept.start)
        assertEquals(anchor(1, 6), swept.end, "the word 'Thanks' was cut in half")
    }

    @Test
    fun `snapping past the end of a block is clamped, not thrown`() {
        // Blocks are re-derived when a book is reprocessed, and a drag can outrun the
        // page's idea of where the text ends.
        val at = Selection.snappedToWord(blocks, anchor(0, 900), towardsEnd = true)
        assertEquals(blocks[0].length, at.charOffset)

        val missing = Selection.snappedToWord(blocks, anchor(9, 3), towardsEnd = true)
        assertEquals(anchor(9, 3), missing, "a block that is not there should pass through")
    }

    @Test
    fun `snapping direction decides which edge of the word wins`() {
        // Offset 12 is inside "cream" (10..14). Going forward takes its end, going
        // back takes its start; the same point means two different things depending
        // on which way the finger is travelling.
        assertEquals(15, Selection.snappedToWord(blocks, anchor(0, 12), true).charOffset)
        assertEquals(10, Selection.snappedToWord(blocks, anchor(0, 12), false).charOffset)
    }
}
