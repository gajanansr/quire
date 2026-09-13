package app.quire.android.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import app.quire.core.reading.TextAnchor
import kotlin.math.abs

/**
 * Which words are where on the page currently drawn.
 *
 * Selection needs to answer one question — the reader touched *here*, what character
 * is that? — and the only thing that can answer it is the [TextLayoutResult] Compose
 * produced while laying each block out. Each drawn block registers its layout and
 * where it sits, and this turns a point into a [TextAnchor] in chapter coordinates.
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

    fun put(entry: Entry) {
        entries[entry.blockIndex] = entry
    }

    fun clear() = entries.clear()

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * The character under [point], in the root's coordinate space.
     *
     * Falls back to the vertically nearest block when the point is in a margin or
     * between paragraphs, because a drag has to keep extending when the finger
     * strays into whitespace — stopping dead there would make selection feel broken
     * when it is only imprecise.
     */
    fun anchorAt(point: Offset): TextAnchor? {
        if (entries.isEmpty()) return null

        val block = entries.values.firstOrNull { entry ->
            point.y >= entry.topLeft.y && point.y <= entry.topLeft.y + entry.size.height
        } ?: entries.values.minByOrNull { entry ->
            val centre = entry.topLeft.y + entry.size.height / 2f
            abs(point.y - centre)
        } ?: return null

        val local = Offset(
            (point.x - block.topLeft.x).coerceIn(0f, block.size.width.toFloat()),
            (point.y - block.topLeft.y).coerceIn(0f, block.size.height.toFloat()),
        )
        val offset = block.layout.getOffsetForPosition(local)
        return TextAnchor(block.blockIndex, block.sliceStart + offset)
    }

    /** The text of the block a point lands in, for expanding a press to a word. */
    fun blockAt(point: Offset): Int? = anchorAt(point)?.blockIndex
}
