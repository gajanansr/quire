package app.quire.android.ui.reader

import kotlin.math.abs
import kotlin.math.max

/** What a drag across the page turned out to mean. */
enum class DragIntent {
    /** Not yet, or never: the finger has said nothing this page can act on. */
    NONE,
    PAGE_TURN,
    BRIGHTNESS,
}

/** Which way a finished swipe turns the page, if at all. */
enum class PageTurn { PREVIOUS, NONE, NEXT }

/** What a tap on the page means, by where it landed. */
enum class TapZone { PREVIOUS, CHROME, NEXT }

/**
 * Which of the page's gestures a touch is.
 *
 * Four of them share one surface — a tap turns the page, a horizontal drag turns the
 * page, a long press selects, a vertical drag on the right edge dims the screen — and
 * a reader who gets the wrong one blames the app rather than their thumb. Stacking a
 * pointer-input modifier per gesture and letting each guess on its own is how a reader
 * ends up two pages further on when they meant to dim the screen; the drag gestures
 * are classified once, here, and the classification is then held for the rest of the
 * gesture.
 *
 * The long press is not in this decision because it does not need to be: Compose's
 * long-press detector cancels itself the instant the pointer travels past touch slop,
 * so a drag of any kind has already ruled out a selection by the time [intentOf] has
 * anything to say. That is a mechanism, not a timing accident.
 */
object ReaderGestures {

    /**
     * How much of the width is the brightness strip.
     *
     * Narrower than the right quarter that already turns pages by tap, so a reader
     * aiming at one does not keep getting the other, and wide enough that it can be
     * hit without looking — which is the point of a gesture used in the dark.
     */
    private const val RIGHT_EDGE = 0.8f

    fun isRightEdge(x: Float, widthPx: Float): Boolean =
        widthPx > 0f && x >= widthPx * RIGHT_EDGE

    /**
     * What a drag of ([dx], [dy]) from [downX] is, once it has travelled past
     * [slopPx].
     *
     * [NONE][DragIntent.NONE] before slop means undecided rather than ignored: a
     * finger that has not moved far enough has not said what it wants, and committing
     * there would classify every tap as a drag in whichever direction the thumb
     * rolled off.
     *
     * Ties go to the page turn. It is the commoner intent and the recoverable one — a
     * page turned by accident costs one tap, while a screen dimmed by accident in a
     * dark room costs rather more.
     *
     * Where the finger went *down* decides the edge, not where it is now. Reading the
     * live position instead would let a page-turn swipe that happens to end near the
     * right bezel become a brightness drag half way through.
     */
    fun intentOf(
        downX: Float,
        widthPx: Float,
        dx: Float,
        dy: Float,
        slopPx: Float,
    ): DragIntent {
        if (max(abs(dx), abs(dy)) < slopPx) return DragIntent.NONE
        if (abs(dx) >= abs(dy)) return DragIntent.PAGE_TURN
        // A vertical drag anywhere else has no meaning: a paginated page has nothing
        // to scroll, and giving it one would put the brightness control wherever a
        // thumb happened to rest.
        return if (isRightEdge(downX, widthPx)) DragIntent.BRIGHTNESS else DragIntent.NONE
    }

    /** Which way a horizontal swipe of [dx] turns, once the finger has lifted. */
    fun turnFor(dx: Float, threshold: Float): PageTurn = when {
        dx < -threshold -> PageTurn.NEXT
        dx > threshold -> PageTurn.PREVIOUS
        else -> PageTurn.NONE
    }

    /**
     * Thirds: the outer columns turn pages, the middle toggles the chrome.
     *
     * Turning by tap matters more than it sounds — it is the gesture a thumb can make
     * without shifting grip. An unmeasured page answers [TapZone.CHROME], because
     * showing the controls is better than turning a page the reader cannot see yet.
     */
    fun tapZone(x: Float, widthPx: Float): TapZone = when {
        widthPx <= 0f -> TapZone.CHROME
        x < widthPx * 0.25f -> TapZone.PREVIOUS
        x > widthPx * 0.75f -> TapZone.NEXT
        else -> TapZone.CHROME
    }
}
