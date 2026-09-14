package app.quire.android.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four gestures share the reading page: a tap turns it, a horizontal drag turns it,
 * a long press selects, and a vertical drag on the right edge dims the screen.
 *
 * Three stacked pointer-input modifiers each guessing on their own is how a reader
 * ends up two pages further on when they meant to dim the screen. The decision is
 * made once, here, from the first movement past touch slop, and then held for the
 * rest of the gesture — a drag re-classified every frame flickers between turning the
 * page and dimming it, which is worse than either.
 */
class ReaderGesturesTest {

    private val width = 1080f
    private val slop = 24f

    private fun intent(downX: Float, dx: Float, dy: Float) =
        ReaderGestures.intentOf(downX, width, dx, dy, slop)

    @Test
    fun `a movement smaller than touch slop decides nothing yet`() {
        // Not "does nothing" — undecided. A finger that has not travelled far enough
        // has not said what it wants, and committing here would classify every tap as
        // a drag in whichever direction the thumb rolled.
        assertEquals(DragIntent.NONE, intent(downX = 1000f, dx = 3f, dy = 5f))
    }

    @Test
    fun `a horizontal drag turns the page, wherever it starts`() {
        assertEquals(DragIntent.PAGE_TURN, intent(downX = 100f, dx = -90f, dy = 8f))
        assertEquals(DragIntent.PAGE_TURN, intent(downX = 1050f, dx = -90f, dy = 8f))
    }

    @Test
    fun `a vertical drag on the right edge adjusts brightness`() {
        assertEquals(DragIntent.BRIGHTNESS, intent(downX = 1050f, dx = 4f, dy = 120f))
    }

    @Test
    fun `a vertical drag away from the edge does nothing`() {
        // There is nothing to scroll on a paginated page, so a vertical drag in the
        // middle has no meaning. Giving it one would put the brightness control
        // wherever a thumb happened to rest.
        assertEquals(DragIntent.NONE, intent(downX = 400f, dx = 4f, dy = 120f))
    }

    @Test
    fun `a drag that starts off the edge never dims, however far right it wanders`() {
        // Where the finger went down is what decides. Reading the live position
        // instead would let a page-turn swipe that ends near the right bezel turn
        // into a brightness drag half way through.
        assertEquals(DragIntent.NONE, intent(downX = 200f, dx = 30f, dy = 400f))
    }

    @Test
    fun `an even diagonal turns the page`() {
        // Ties go to the page turn: it is the commoner intent and the recoverable
        // one. A page turned by accident costs one tap; a screen dimmed by accident
        // in a dark room costs rather more.
        assertEquals(DragIntent.PAGE_TURN, intent(downX = 1050f, dx = 100f, dy = 100f))
    }

    @Test
    fun `the right edge is an edge, not the right third`() {
        // The tap zone for turning pages is already the right quarter. The brightness
        // strip has to be narrower than that or a reader aiming at one would keep
        // getting the other.
        assertTrue(ReaderGestures.isRightEdge(1000f, width))
        assertFalse(ReaderGestures.isRightEdge(700f, width))
        assertTrue("the boundary itself is inside the strip", ReaderGestures.isRightEdge(864f, width))
    }

    @Test
    fun `an unmeasured page has no right edge`() {
        assertFalse(ReaderGestures.isRightEdge(0f, 0f))
    }

    // ------------------------------------------------------------ page turns

    @Test
    fun `a swipe left goes forward and a swipe right goes back`() {
        assertEquals(PageTurn.NEXT, ReaderGestures.turnFor(-120f, threshold = 80f))
        assertEquals(PageTurn.PREVIOUS, ReaderGestures.turnFor(120f, threshold = 80f))
    }

    @Test
    fun `a short swipe turns nothing`() {
        assertEquals(PageTurn.NONE, ReaderGestures.turnFor(-40f, threshold = 80f))
    }

    // ---------------------------------------------------------------- taps

    @Test
    fun `the outer columns turn pages and the middle shows the chrome`() {
        assertEquals(TapZone.PREVIOUS, ReaderGestures.tapZone(100f, width))
        assertEquals(TapZone.CHROME, ReaderGestures.tapZone(540f, width))
        assertEquals(TapZone.NEXT, ReaderGestures.tapZone(1000f, width))
    }

    @Test
    fun `an unmeasured page treats every tap as the middle`() {
        // Better to show the chrome than to turn a page the reader cannot see yet.
        assertEquals(TapZone.CHROME, ReaderGestures.tapZone(0f, 0f))
    }
}
