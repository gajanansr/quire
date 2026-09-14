package app.quire.android.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brightness ramp.
 *
 * *"scroll down on right side should adjust the brightness"* — the convention in
 * Moon+ Reader and KOReader, and the reason a reader reaches for the right edge
 * without being told to.
 *
 * The one thing this must never do is let a reader dim the screen to nothing. The
 * gesture that would undo it is invisible on a black screen, and so is the back
 * button, so a floor of zero hands the device back unusable.
 */
class ScreenBrightnessTest {

    private val delta = 0.0001f

    @Test
    fun `dragging down dims`() {
        // Down is darker, everywhere this convention exists. Getting it backwards
        // would be the kind of wrong that is obvious in one second on a phone and
        // invisible in a code review.
        assertEquals(0.7f, ScreenBrightness.dragged(0.8f, dragPx = 100f, trackPx = 1000f), delta)
    }

    @Test
    fun `dragging up brightens`() {
        assertEquals(0.9f, ScreenBrightness.dragged(0.8f, dragPx = -100f, trackPx = 1000f), delta)
    }

    @Test
    fun `one sweep of the page covers the whole range`() {
        // Any less and dimming needs several strokes; any more and a page-turn-sized
        // wobble blacks out the screen.
        assertEquals(
            ScreenBrightness.FLOOR,
            ScreenBrightness.dragged(1f, dragPx = 1800f, trackPx = 1800f),
            delta,
        )
    }

    @Test
    fun `the screen cannot be dragged to black`() {
        val floored = ScreenBrightness.dragged(0.2f, dragPx = 99999f, trackPx = 1000f)
        assertEquals(ScreenBrightness.FLOOR, floored, delta)
        assertTrue("the floor is darkness, which is a screen nobody can get back", floored > 0f)
    }

    @Test
    fun `from the floor the screen still comes back up`() {
        // The other half of not stranding the reader: the floor has to be a floor,
        // not a trap.
        val recovered = ScreenBrightness.dragged(ScreenBrightness.FLOOR, -200f, 1000f)
        assertTrue("the dimmest setting could not be undone", recovered > ScreenBrightness.FLOOR)
    }

    @Test
    fun `brightness stops at full`() {
        assertEquals(1f, ScreenBrightness.dragged(0.9f, dragPx = -99999f, trackPx = 1000f), delta)
    }

    @Test
    fun `an unmeasured page changes nothing`() {
        // The track is zero for the frame before the reading column is measured.
        // Dividing by it would set brightness to NaN, which the window manager takes
        // as "whatever you like".
        assertEquals(0.4f, ScreenBrightness.dragged(0.4f, dragPx = 100f, trackPx = 0f), delta)
    }

    @Test
    fun `the follow-the-system sentinel is not mistaken for darkness`() {
        // Until the first drag Quire sets no brightness at all, and the window carries
        // -1. Treating that as a level would make the first drag start from below the
        // floor and snap the screen to its dimmest.
        val first = ScreenBrightness.dragged(ScreenBrightness.FOLLOW_SYSTEM, 0f, 1000f)
        assertEquals(ScreenBrightness.DEFAULT, first, delta)
    }

    @Test
    fun `a system brightness reading is normalised into range`() {
        assertEquals(0.5f, ScreenBrightness.seed(raw = 128, max = 255), 0.01f)
        assertEquals(1f, ScreenBrightness.seed(raw = 255, max = 255), delta)
    }

    @Test
    fun `a system brightness of zero still lands on the floor`() {
        assertEquals(ScreenBrightness.FLOOR, ScreenBrightness.seed(raw = 0, max = 255), delta)
    }

    @Test
    fun `a nonsense system reading falls back rather than guessing`() {
        // Settings.System.SCREEN_BRIGHTNESS is nominally 0..255 but is not guaranteed,
        // and on a device with adaptive brightness on it may not be what is on screen
        // at all. A wrong seed is recoverable — the reader drags again — but a crash
        // or a black page is not.
        assertEquals(ScreenBrightness.DEFAULT, ScreenBrightness.seed(raw = -1, max = 255), delta)
        assertEquals(ScreenBrightness.DEFAULT, ScreenBrightness.seed(raw = 100, max = 0), delta)
        assertEquals(1f, ScreenBrightness.seed(raw = 9999, max = 255), delta)
    }
}
