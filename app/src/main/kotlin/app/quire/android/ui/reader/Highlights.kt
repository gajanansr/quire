package app.quire.android.ui.reader

import app.quire.android.data.SavedHighlight
import app.quire.core.reading.TextAnchor

/**
 * Which saved mark a tap landed on.
 *
 * A highlight has to be changeable after the fact, and the gesture that says "this
 * one" is tapping it. Everything about that is arithmetic — the page already resolves
 * a touch to a character through [PageTextMap], and from there it is a question about
 * spans — so it lives here as a pure function rather than inside a pointer handler
 * where nothing can reach it.
 */
object Highlights {

    /**
     * The mark covering [at], or null if the tap missed every one of them.
     *
     * **The shortest wins.** Highlights nest: a reader marks a paragraph and later
     * marks one sentence inside it in another colour. Taking the first match would
     * make the inner mark permanently unreachable — there is no gesture that could
     * ever select it — so the tie goes to the smaller, which is always the one the
     * reader had to work harder to make.
     *
     * A null [at] is a tap the page could not resolve into a character: the margin,
     * or the gap below the last paragraph. It hits nothing, which is what closes the
     * options rather than reopening them.
     */
    fun at(highlights: List<SavedHighlight>, at: TextAnchor?): SavedHighlight? {
        if (at == null) return null
        return highlights
            .filter { at in it.span }
            .minByOrNull { lengthOf(it) }
    }

    /**
     * How much of a chapter a span covers, for comparison only.
     *
     * Blocks count for far more than characters because the character offsets of two
     * different blocks are not comparable — offset 40 of block 3 and offset 40 of
     * block 9 are different distances into the chapter, and only the block count says
     * which span is the bigger. The multiplier only has to be larger than any
     * plausible block length for that ordering to hold.
     */
    private fun lengthOf(highlight: SavedHighlight): Long {
        val blocks = highlight.span.end.blockIndex - highlight.span.start.blockIndex
        val chars = highlight.span.end.charOffset - highlight.span.start.charOffset
        return blocks * BLOCK_WEIGHT + chars
    }

    /** Longer than any paragraph a reflowed book produces. */
    private const val BLOCK_WEIGHT = 1_000_000L
}
