package app.quire.core.model

import app.quire.core.reading.TextAnchor
import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val index: Int,
    val title: String?,
    val blocks: List<ContentBlock>,
    val startCharOffset: Int,
    val charCount: Int,
) {
    /**
     * Each block's text, built once for the life of the chapter.
     *
     * [plainText] is a computed property: every read rebuilds the string from its
     * spans. The Reader reads it per visible block per recomposition, so a page turn
     * rebuilt the full text of everything on screen — on a single 400k-character
     * block, a 400k-character allocation per frame. Pagination reads it too, once
     * per block per repagination.
     *
     * Outside the constructor and lazy, so it is derived rather than stored: it
     * takes no part in equality, and kotlinx.serialization writes only constructor
     * properties, so nothing extra reaches the JSON on disk.
     */
    val blockTexts: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        blocks.map { it.plainText }
    }

    /**
     * Where each block begins, counted in characters from the chapter's first.
     *
     * Derived once, like [blockTexts], because the two questions it answers are both
     * asked on the page-turn path of a book whose chapter can be 8,621 blocks long:
     *
     * - **How far into the chapter is the reader?** `ReaderState.progress` used to
     *   answer this with `slice.startChar`, which is the offset inside the reader's
     *   *own block*. On a book of many small chapters that is nearly right; on a book
     *   that is one chapter it means a reader three hundred screens in still reads
     *   0%, because the number never exceeds the length of one paragraph.
     * - **Which character is *n* before the reader?** That is where a pagination
     *   window is anchored, and summing block lengths to find it would be O(blocks)
     *   on every anchor.
     *
     * A prefix sum plus a binary search makes both O(log blocks) after one pass.
     */
    val blockStarts: List<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        var running = 0
        blockTexts.map { text ->
            val start = running
            running += text.length
            start
        }
    }

    /** Every character in the chapter's blocks. Not [charCount], which is stored. */
    val textLength: Int
        get() = blockStarts.lastOrNull()?.let { it + blockTexts.last().length } ?: 0

    /**
     * How far into the chapter a position is, in characters.
     *
     * Both arguments are clamped rather than trusted. A position saved before a book
     * was reprocessed can name a block that no longer exists or an offset past the
     * end of the one it names, and an offset larger than the chapter would read as
     * progress past the end of the book.
     */
    fun offsetOf(blockIndex: Int, charOffset: Int): Int {
        if (blockTexts.isEmpty()) return 0
        if (blockIndex >= blockTexts.size) return textLength
        val index = blockIndex.coerceAtLeast(0)
        return blockStarts[index] + charOffset.coerceIn(0, blockTexts[index].length)
    }

    /**
     * The position at a character offset into the chapter.
     *
     * Never lands inside a block with no characters — a page break, an uncaptioned
     * image — because a cursor there would be ambiguous with the start of the next
     * block and pagination would do different things with the two. The offset at such
     * a boundary belongs to the next block that has text.
     *
     * [TextAnchor] rather than a type of its own: a selection, a bookmark, a resume
     * point and a chunk boundary are all "which block, how far in", and a fifth
     * spelling of the same pair is a fifth chance for one of them to be converted
     * wrongly.
     */
    fun cursorAt(offset: Int): TextAnchor {
        if (blockTexts.isEmpty()) return TextAnchor(0, 0)
        val target = offset.coerceIn(0, textLength)
        // The last block whose start is at or before the target, then walked forward
        // over any block with no characters so the cursor sits on real text.
        var lo = 0
        var hi = blockStarts.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (blockStarts[mid] <= target) lo = mid else hi = mid - 1
        }
        var index = lo
        while (index < blockTexts.lastIndex && blockTexts[index].isEmpty()) index++
        return TextAnchor(index, target - blockStarts[index])
    }
}

/** A chapter's identity without its content, so the library can list without loading. */
@Serializable
data class ChapterRef(
    val index: Int,
    val title: String?,
    val startCharOffset: Int,
    val charCount: Int,
)

fun Chapter.toRef() = ChapterRef(index, title, startCharOffset, charCount)
