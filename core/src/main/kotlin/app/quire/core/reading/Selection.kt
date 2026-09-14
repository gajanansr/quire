package app.quire.core.reading

/**
 * A place in a chapter: which block, and how far into it.
 *
 * The same coordinates reading position already uses, so a selection, a bookmark and
 * a resume point are all the same kind of thing and none of them needs translating.
 */
data class TextAnchor(val blockIndex: Int, val charOffset: Int) : Comparable<TextAnchor> {
    override fun compareTo(other: TextAnchor): Int =
        compareValuesBy(this, other, { it.blockIndex }, { it.charOffset })
}

/**
 * A stretch of a chapter, from [start] up to but not including [end].
 *
 * Always normalised, because a reader dragging backwards produces the same selection
 * as one dragging forwards and nothing downstream should have to care which happened.
 */
data class TextSpan(val start: TextAnchor, val end: TextAnchor) {

    val isEmpty: Boolean get() = start == end

    companion object {
        fun of(a: TextAnchor, b: TextAnchor): TextSpan =
            if (a <= b) TextSpan(a, b) else TextSpan(b, a)
    }
}

/**
 * Where a word begins and ends.
 *
 * A long-press takes the word under the finger rather than the character, because
 * nobody aims at a character — a fingertip covers several of them. Pressing on the
 * space between two words takes neither: an empty range there is better than
 * arbitrarily picking the one on the left.
 */
object WordBoundary {

    /**
     * Whether [c] can be part of a word.
     *
     * Apostrophes count, both kinds. "India's" is one word, and the curly apostrophe
     * real books use is not a letter — treating it as a break makes "India" and "s"
     * out of one possessive, which is also the bug that once made a word-count
     * diagnostic report a thousand broken words that were not there.
     */
    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '\'' || c == '’'

    fun expand(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val at = offset.coerceIn(0, text.length)

        // On a boundary, look at the character behind the caret as well: tapping the
        // right-hand edge of a word should take that word, not the gap after it.
        val index = when {
            at < text.length && isWordChar(text[at]) -> at
            at > 0 && isWordChar(text[at - 1]) -> at - 1
            else -> return IntRange.EMPTY
        }

        var from = index
        while (from > 0 && isWordChar(text[from - 1])) from--
        var to = index + 1
        while (to < text.length && isWordChar(text[to])) to++
        return from until to
    }
}

/**
 * Mapping a selection onto the page that draws it.
 *
 * A selection is stated in whole-chapter coordinates. A page draws a *slice* of each
 * block — pagination splits a long paragraph across pages — so before a highlight can
 * be painted the span has to be cut down to the part of the block this page shows,
 * and re-based to that part's own indices. That arithmetic is the whole of this
 * object, and it is the piece most likely to be wrong in a way that looks almost
 * right: an off-by-the-slice-start highlight lands on the wrong words.
 */
object Selection {

    /**
     * The part of [span] that falls inside the `[sliceStart, sliceEnd)` portion of
     * block [blockIndex], expressed relative to that portion.
     *
     * Null when they do not overlap, which is the common case: most blocks on a page
     * are not selected at all.
     */
    fun portionOf(
        span: TextSpan,
        blockIndex: Int,
        sliceStart: Int,
        sliceEnd: Int,
    ): IntRange? {
        if (span.isEmpty) return null
        if (blockIndex < span.start.blockIndex || blockIndex > span.end.blockIndex) return null

        // A block strictly inside the span is selected end to end; only the first and
        // last blocks of a span are cut by an offset.
        val from = if (blockIndex == span.start.blockIndex) span.start.charOffset else 0
        val to = if (blockIndex == span.end.blockIndex) span.end.charOffset else Int.MAX_VALUE

        val lo = maxOf(from, sliceStart)
        val hi = minOf(to, sliceEnd)
        if (lo >= hi) return null

        return (lo - sliceStart) until (hi - sliceStart)
    }

    /**
     * The selected text, rebuilt from the chapter's blocks.
     *
     * Blocks are joined with a blank line: they are paragraphs, and running two of
     * them together with a space would turn a passage of dialogue into one sentence.
     */
    fun textOf(blockTexts: List<String>, span: TextSpan): String {
        if (span.isEmpty) return ""
        val first = span.start.blockIndex.coerceIn(0, blockTexts.lastIndex)
        val last = span.end.blockIndex.coerceIn(0, blockTexts.lastIndex)

        return (first..last).mapNotNull { index ->
            val text = blockTexts[index]
            val from = if (index == span.start.blockIndex) span.start.charOffset else 0
            val to = if (index == span.end.blockIndex) span.end.charOffset else text.length
            val lo = from.coerceIn(0, text.length)
            val hi = to.coerceIn(lo, text.length)
            text.substring(lo, hi).takeIf { it.isNotBlank() }
        }.joinToString("\n\n") { it.trim() }
    }

    /**
     * One end of [span] moved to [to], the other left exactly where it is.
     *
     * This is what a grabbed handle does, and it is character-accurate on purpose:
     * the handles are the only way to say "one letter further", and a reader reaches
     * for them precisely because the word-sized sweep overshot.
     *
     * Two cases have to be right or the selection appears to break under the finger.
     * Dragging a handle *past* the other one swaps which end is held rather than
     * stopping dead at the crossing point — the finger is already past it by the time
     * the crossing is detected, so refusing to follow reads as the app seizing up.
     * And a drag exactly onto the anchor is refused outright: an empty span makes
     * `hasSelection` false, which would take the handles and the action bar off
     * screen mid-gesture, looking like the selection had been destroyed.
     */
    fun movingEdge(span: TextSpan, edge: SelectionEdge, to: TextAnchor): SelectionDrag {
        val anchor = if (edge == SelectionEdge.START) span.end else span.start
        if (to == anchor) return SelectionDrag(span, edge)
        val held = if (to < anchor) SelectionEdge.START else SelectionEdge.END
        return SelectionDrag(TextSpan.of(anchor, to), held)
    }

    /**
     * [at], pushed out to the near edge of whatever word it is inside.
     *
     * [towardsEnd] is the direction the finger is travelling: going forward takes the
     * word's end, going back takes its start, so the same point means two different
     * things depending on which way the reader is sweeping. That is the whole reason
     * a word-granular sweep feels like Android's — the selection only ever grows in
     * the direction of travel.
     *
     * An offset in whitespace is left alone. Snapping it would swallow the next word
     * before the finger had reached it, which is the specific overshoot that makes a
     * word-snapping selection feel greedy.
     */
    fun snappedToWord(
        blockTexts: List<String>,
        at: TextAnchor,
        towardsEnd: Boolean,
    ): TextAnchor {
        val text = blockTexts.getOrNull(at.blockIndex) ?: return at
        val offset = at.charOffset.coerceIn(0, text.length)
        val word = WordBoundary.expand(text, offset)
        if (word.isEmpty()) return TextAnchor(at.blockIndex, offset)
        return TextAnchor(at.blockIndex, if (towardsEnd) word.last + 1 else word.first)
    }

    /**
     * The selection produced by sweeping from the pressed word [origin] out to [to].
     *
     * The pressed word is always part of the result, in both directions. The old
     * model anchored on that word's *start*, so sweeping backwards selected up to the
     * start and the word under the finger silently dropped out of its own selection.
     */
    fun sweptTo(blockTexts: List<String>, origin: TextSpan, to: TextAnchor): TextSpan {
        val snapped = snappedToWord(blockTexts, to, towardsEnd = to >= origin.end)
        return TextSpan(minOf(origin.start, snapped), maxOf(origin.end, snapped))
    }
}

/**
 * Which end of a live selection a drag is holding.
 *
 * The old model had no name for this because there was only one answer: the long
 * press pinned the start and the far end followed the finger. A selection with
 * grabbable handles has two answers, and the whole of [Selection.movingEdge] is
 * keeping them straight.
 */
enum class SelectionEdge { START, END }

/**
 * A selection mid-drag: where it is now, and which end the finger still holds.
 *
 * The edge comes back out because it can change during the drag — dragging one
 * handle past the other makes it the *other* end — and the caller has to keep
 * holding the handle the finger is actually on.
 */
data class SelectionDrag(val span: TextSpan, val edge: SelectionEdge)
