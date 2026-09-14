package app.quire.android.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import app.quire.core.reading.SelectionEdge
import app.quire.core.reading.TextAnchor

/**
 * Which words are where on the page currently drawn.
 *
 * Selection needs to answer two questions — the reader touched *here*, what character
 * is that, and this character, where is it on screen? — and the only thing that can
 * answer either is the [TextLayoutResult] Compose produced while laying each block
 * out. Each drawn block registers its layout and where it sits; [anchorAt] turns a
 * point into a [TextAnchor] in chapter coordinates and [caretAt] turns one back into
 * a place to draw a handle.
 *
 * Rebuilt for every page rather than kept: the entries describe one arrangement of
 * one page, and a stale entry would resolve a touch to a character that is no longer
 * under it.
 */
class PageTextMap {

    /**
     * One drawn block.
     *
     * [sliceStart] is what makes the coordinates chapter-wide rather than page-wide:
     * a page may draw characters 100..180 of a long paragraph, and the layout's own
     * offset 0 is that paragraph's character 100.
     */
    data class Entry(
        val blockIndex: Int,
        val sliceStart: Int,
        val topLeft: Offset,
        val size: IntSize,
        val layout: TextLayoutResult,
    )

    private val entries = mutableMapOf<Int, Entry>()

    /**
     * Where the page's own top-left sits in the root, and the fix for a bug that was
     * most of *"the text selection doesnt work well"*.
     *
     * A block registers `positionInRoot()`, but a touch arrives in the coordinates of
     * the composable that caught it — and the Reader does not start at the root: the
     * whole app sits inside a `statusBarsPadding()`. So every touch was compared
     * against positions a status bar taller than itself, and a long press selected a
     * word one to three lines *above* the finger. Everything here is stated in the
     * page's own coordinates, and this is the one place the two are reconciled.
     *
     * Bumps [revision] on a change so a selection made before the origin was known is
     * re-measured rather than left drawing its handles in the wrong place.
     */
    var origin: Offset = Offset.Zero
        set(value) {
            if (field == value) return
            field = value
            revision++
        }

    /**
     * Bumped whenever the page's layout actually changes.
     *
     * The handles are drawn from this map, and a plain map cannot tell Compose that
     * it now knows where the text is — so the first frame after a page is laid out
     * would draw a selection with no handles on it. Reading this as a `remember` key
     * subscribes the overlay to that.
     *
     * Only on a real change. Registration happens from `onGloballyPositioned`, which
     * runs on every layout pass, so bumping unconditionally would recompose, re-lay
     * out, bump again, and never settle.
     */
    var revision: Int by mutableIntStateOf(0)
        private set

    fun put(entry: Entry) {
        if (entries[entry.blockIndex] == entry) return
        entries[entry.blockIndex] = entry
        revision++
    }

    fun clear() {
        if (entries.isEmpty()) return
        entries.clear()
        revision++
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * The character under [point], in the root's coordinate space.
     *
     * Falls back to the vertically nearest block when the point is in a margin or
     * between paragraphs, because a drag has to keep extending when the finger
     * strays into whitespace — stopping dead there would make selection feel broken
     * when it is only imprecise. Which block is "nearest" is [PageHitTest]'s
     * decision, and it is a real one: measuring to block centres sent a drag through
     * a paragraph gap to whichever neighbour happened to be shorter.
     */
    fun anchorAt(point: Offset): TextAnchor? {
        val index = PageHitTest.blockFor(bands(), point.y) ?: return null
        val block = entries[index] ?: return null

        val topLeft = block.topLeft - origin
        val local = Offset(
            (point.x - topLeft.x).coerceIn(0f, block.size.width.toFloat()),
            (point.y - topLeft.y).coerceIn(0f, block.size.height.toFloat()),
        )
        val offset = block.layout.getOffsetForPosition(local)
        return TextAnchor(block.blockIndex, block.sliceStart + offset)
    }

    /**
     * Where [anchor] sits on the page, or null when this page does not draw it.
     *
     * Null is a normal answer, not a failure: a selection made near the foot of a page
     * can run past its end, and a handle pinned to the margin would be a lie about
     * where the passage stops.
     *
     * [edge] decides which side of a character the caret sits on, and it matters at a
     * soft line break. A selection ending exactly at a wrap has its end offset at the
     * *start of the next line*, so a cursor rect for it would draw the closing handle
     * at the left margin one line below the last selected word. Taking the trailing
     * edge of the preceding character instead keeps the handle on the line it ends.
     *
     * The horizontal edges assume left-to-right text, which is what every book Quire
     * can currently import is set in.
     */
    fun caretAt(anchor: TextAnchor, edge: SelectionEdge): CaretRect? {
        val block = entries[anchor.blockIndex] ?: return null
        val length = block.layout.layoutInput.text.length
        if (length == 0) return null

        val local = anchor.charOffset - block.sliceStart
        if (local < 0 || local > length) return null

        // Whether the caret is the leading edge of the character at the offset or the
        // trailing edge of the one before it. Both ends collapse onto the available
        // side when the offset is at a boundary of the drawn slice.
        val trailing = when {
            edge == SelectionEdge.END -> local > 0
            else -> local >= length
        }
        val box = block.layout.getBoundingBox(
            (if (trailing) local - 1 else local).coerceIn(0, length - 1),
        )
        val topLeft = block.topLeft - origin
        return CaretRect(
            x = topLeft.x + if (trailing) box.right else box.left,
            top = topLeft.y + box.top,
            bottom = topLeft.y + box.bottom,
        )
    }

    private fun bands(): List<Band> = entries.values.map {
        val top = it.topLeft.y - origin.y
        Band(it.blockIndex, top, top + it.size.height)
    }
}
