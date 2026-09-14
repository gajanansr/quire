package app.quire.android.ui.reader

import androidx.compose.ui.geometry.Offset
import app.quire.core.reading.SelectionEdge
import kotlin.math.abs
import kotlin.math.min

/**
 * Where a selection is on screen, and what a finger near it means.
 *
 * All of this is pixel arithmetic against a page that only exists on a device, which
 * is exactly the kind of code that looks right and is off by a block. There is no
 * Compose UI test dependency here and none is being added, so every decision lives in
 * a pure function `SelectionGeometryTest` can reach and the composable only reads it.
 */

/**
 * Where one character sits, in the root's coordinate space.
 *
 * A caret rather than a point: a handle has to be drawn hanging below the *line* the
 * character is on, and the line's height is not derivable from a point.
 */
data class CaretRect(val x: Float, val top: Float, val bottom: Float) {
    val height: Float get() = bottom - top
}

/** The vertical extent of one drawn block, and which block it is. */
data class Band(val blockIndex: Int, val top: Float, val bottom: Float)

/** Which block a touch on the page belongs to. */
object PageHitTest {

    /**
     * The index of the block at [y], or null when the page has drawn nothing.
     *
     * Containment first. Failing that the *nearest edge* wins, and the distinction
     * from nearest centre is the bug this exists to fix: a tall paragraph's centre can
     * be hundreds of pixels away while its top edge is under the finger, so measuring
     * to centres sent a drag through the gap between two paragraphs to whichever one
     * happened to be shorter. A finger in a margin or a paragraph gap has to keep
     * extending the selection into the block it is actually beside.
     */
    fun blockFor(bands: List<Band>, y: Float): Int? {
        if (bands.isEmpty()) return null
        bands.firstOrNull { y >= it.top && y <= it.bottom }?.let { return it.blockIndex }
        return bands.minByOrNull { min(abs(y - it.top), abs(y - it.bottom)) }?.blockIndex
    }
}

/** The two grabbable ends of a live selection. */
object SelectionHandles {

    /**
     * How far past the drawn radius a press still counts as a grab.
     *
     * The drawing is the affordance; the touch target is what has to be thumb-sized.
     * A 10dp teardrop with a 10dp touch target is a control nobody can hit, and the
     * reader's conclusion is that the handles do not work rather than that they
     * missed.
     */
    private const val GRAB = 2.4f

    /**
     * Where the teardrop for [edge] is drawn, given the caret it marks.
     *
     * Below the line, and leaning away from the text: start to the left, end to the
     * right. That lean is the only reason the two handles of a one-word selection can
     * be told apart by a thumb, and it is also how every other Android app draws them,
     * so it is what a reader's hand already expects.
     */
    fun centreOf(caret: CaretRect, edge: SelectionEdge, radiusPx: Float): Offset = Offset(
        x = if (edge == SelectionEdge.START) caret.x - radiusPx else caret.x + radiusPx,
        y = caret.bottom + radiusPx,
    )

    /**
     * Which handle a press at [down] took, or null for a press that took neither.
     *
     * A null caret means that end of the selection is on a block this page does not
     * draw — the passage runs off the page — and a handle that was never drawn must
     * never be grabbable.
     *
     * When both grab areas contain the press the nearer handle wins. Taking the first
     * one in range instead would make the right-hand handle of a short word
     * permanently unreachable.
     */
    fun grabbed(
        down: Offset,
        start: CaretRect?,
        end: CaretRect?,
        radiusPx: Float,
    ): SelectionEdge? {
        val candidates = listOfNotNull(
            start?.let { SelectionEdge.START to it },
            end?.let { SelectionEdge.END to it },
        ).mapNotNull { (edge, caret) ->
            val centre = centreOf(caret, edge, radiusPx)
            val reach = radiusPx * GRAB
            // The region runs from the top of the marked line down past the teardrop,
            // so a thumb landing short — on the last line rather than below it — still
            // grabs rather than clearing the selection.
            val inside = abs(down.x - centre.x) <= reach &&
                down.y >= caret.top && down.y <= centre.y + reach
            if (!inside) null else edge to (down - centre).getDistance()
        }
        return candidates.minByOrNull { it.second }?.first
    }
}

/** Where the selection's action bar can sit without covering the passage. */
object SelectionActionBar {

    /**
     * Whether the actions belong at the top of the page.
     *
     * A bar pinned to the bottom sits on the words it is offering to copy whenever the
     * passage reaches into the lower part of the page, which is the one place it must
     * never be. Above that threshold it moves out of the way.
     *
     * A viewport of zero — the single frame before the page is first measured —
     * answers "no", because a bar that jumps to the top and back on that frame is a
     * visible flicker on every selection.
     */
    fun prefersTop(selectionBottomPx: Float, viewportHeightPx: Float): Boolean =
        viewportHeightPx > 0f && selectionBottomPx > viewportHeightPx * LOW_ON_THE_PAGE

    private const val LOW_ON_THE_PAGE = 0.62f
}
