package app.quire.android.ui.reader

import androidx.compose.ui.geometry.Offset
import app.quire.core.reading.SelectionEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometry a selection needs and a `TextLayoutResult` cannot be asked for.
 *
 * Every decision here is made against pixel coordinates, which is exactly the kind
 * of code that looks right and is off by a block. There is no Compose UI test
 * dependency in this project, so the geometry lives in pure functions and the
 * composable does nothing but read them.
 */
class SelectionGeometryTest {

    // ------------------------------------------------- which block a touch is in

    @Test
    fun `a touch inside a block belongs to that block`() {
        val bands = listOf(Band(0, 0f, 100f), Band(1, 120f, 300f))
        assertEquals("a touch inside the second block resolved elsewhere", 1, PageHitTest.blockFor(bands, 200f))
    }

    @Test
    fun `a touch in the gap between blocks takes the nearer edge, not the nearer centre`() {
        // The bug this replaces. A short heading at the top and a tall paragraph
        // below it: the finger is 50px above the paragraph and 110px below the
        // heading, so the paragraph is plainly what it is nearest. Measuring to
        // block *centres* says otherwise — the paragraph's centre is 450px away —
        // and a drag through the gap jumped to the heading instead.
        val heading = Band(0, 0f, 40f)
        val paragraph = Band(1, 200f, 1000f)
        val bands = listOf(heading, paragraph)

        assertEquals("centre distance won over edge distance", 1, PageHitTest.blockFor(bands, 150f))
    }

    @Test
    fun `a touch above every block lands on the first`() {
        val bands = listOf(Band(0, 100f, 200f), Band(1, 220f, 400f))
        assertEquals(0, PageHitTest.blockFor(bands, -50f))
    }

    @Test
    fun `a touch below every block lands on the last`() {
        // A drag that runs off the bottom of the page has to keep extending to the
        // end of the text rather than stopping dead at the last paragraph's edge.
        val bands = listOf(Band(0, 100f, 200f), Band(1, 220f, 400f))
        assertEquals(1, PageHitTest.blockFor(bands, 900f))
    }

    @Test
    fun `an empty page resolves nothing`() {
        assertNull(PageHitTest.blockFor(emptyList(), 100f))
    }

    @Test
    fun `blocks are found by their own index, not their position in the list`() {
        // Entries are keyed by block index and a page rarely starts at block 0.
        val bands = listOf(Band(7, 0f, 100f), Band(8, 120f, 300f))
        assertEquals(8, PageHitTest.blockFor(bands, 200f))
    }

    // ------------------------------------------------------ grabbing a handle

    private val radius = 12f
    private val start = CaretRect(x = 100f, top = 200f, bottom = 240f)
    private val end = CaretRect(x = 400f, top = 200f, bottom = 240f)

    @Test
    fun `a press on the end handle grabs the end`() {
        val at = SelectionHandles.centreOf(end, SelectionEdge.END, radius)
        assertEquals(SelectionEdge.END, SelectionHandles.grabbed(at, start, end, radius))
    }

    @Test
    fun `a press on the start handle grabs the start`() {
        val at = SelectionHandles.centreOf(start, SelectionEdge.START, radius)
        assertEquals(SelectionEdge.START, SelectionHandles.grabbed(at, start, end, radius))
    }

    @Test
    fun `the handles hang below their caret, on opposite sides`() {
        // The drawn teardrops point inwards at the text and lean outwards, which is
        // the only reason the two handles of a one-word selection can be told apart
        // by a thumb.
        val s = SelectionHandles.centreOf(start, SelectionEdge.START, radius)
        val e = SelectionHandles.centreOf(end, SelectionEdge.END, radius)
        assertTrue("the start handle is not left of its caret", s.x < start.x)
        assertTrue("the end handle is not right of its caret", e.x > end.x)
        assertTrue("a handle is drawn over the line it marks", s.y > start.bottom)
    }

    @Test
    fun `a press in the middle of the page grabs nothing`() {
        // Otherwise every tap inside the passage would drag a handle instead of
        // being a tap, and the action bar could never be reached.
        assertNull(SelectionHandles.grabbed(Offset(250f, 220f), start, end, radius))
    }

    @Test
    fun `a press well below the handles grabs nothing`() {
        assertNull(SelectionHandles.grabbed(Offset(100f, 600f), start, end, radius))
    }

    @Test
    fun `with both handles in reach the nearer one wins`() {
        // A short word puts the two handles within a thumb's width of each other —
        // their grab areas overlap, and taking the first one in range rather than the
        // nearest would make one of them unreachable. Carets 16px apart put the drawn
        // centres at 88 and 128, so a press at 110 is nearer the end and 106 nearer
        // the start, with both inside the grab area either way.
        val short = CaretRect(x = 116f, top = 200f, bottom = 240f)
        val y = SelectionHandles.centreOf(short, SelectionEdge.END, radius).y

        assertEquals(
            "a press nearer the end handle took the start",
            SelectionEdge.END,
            SelectionHandles.grabbed(Offset(110f, y), start, short, radius),
        )
        assertEquals(
            "a press nearer the start handle took the end",
            SelectionEdge.START,
            SelectionHandles.grabbed(Offset(106f, y), start, short, radius),
        )
    }

    @Test
    fun `a handle whose end is off the page cannot be grabbed`() {
        // A selection can run past the bottom of the page. Its far caret is null,
        // and a press must not resolve to a handle that was never drawn.
        val at = SelectionHandles.centreOf(end, SelectionEdge.END, radius)
        assertNull(SelectionHandles.grabbed(at, start, null, radius))
    }

    // --------------------------------------------------- where the actions sit

    @Test
    fun `the action bar moves to the top when the passage is low on the page`() {
        // A bar pinned to the bottom sits on top of the words it is offering to
        // copy, which is the one place it must never be.
        assertTrue(
            "the bar stayed over the selection",
            SelectionActionBar.prefersTop(selectionBottomPx = 1600f, viewportHeightPx = 2000f),
        )
    }

    @Test
    fun `the action bar stays at the bottom for a passage high on the page`() {
        assertFalse(
            SelectionActionBar.prefersTop(selectionBottomPx = 300f, viewportHeightPx = 2000f),
        )
    }

    @Test
    fun `a page of unknown height keeps the bar where it was`() {
        // The viewport is zero for one frame before the first measurement, and a bar
        // that jumps to the top and back down again on that frame is a flicker.
        assertFalse(
            SelectionActionBar.prefersTop(selectionBottomPx = 300f, viewportHeightPx = 0f),
        )
    }
}
