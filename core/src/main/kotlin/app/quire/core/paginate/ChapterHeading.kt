package app.quire.core.paginate

/**
 * Whether a chapter already announces itself, so the Reader should not announce it
 * again.
 *
 * The Reader draws a header above the first page of a chapter: a small "Chapter N"
 * label and, beneath it, the chapter's title. Most EPUBs also open the chapter's own
 * content with an `<h1>` saying the same thing, and drawing both prints the title
 * twice — the reported symptom being "Part 1" immediately above "Part 1".
 *
 * The previous rule was `blocks.first()` must be a `Heading` whose trimmed text
 * equals the trimmed title. Real books defeat every clause of that:
 *
 * - **The heading is not block zero.** Chapters routinely open with a page break, an
 *   empty paragraph left by an anchor that reflowed to nothing, or a running page
 *   number lifted off the top of the page.
 * - **The heading is not a `Heading`.** Plenty of books set the chapter title as a
 *   styled paragraph, which reaches us as `ContentBlock.Paragraph`.
 * - **The strings differ invisibly.** `Part&nbsp;1` against `Part 1`; a trailing full
 *   stop or colon; a soft hyphen; a doubled space from a line break inside the tag.
 *
 * So: find the first block that carries words, and compare what a reader would call
 * the same string. Comparison is on letters and digits only, case-folded — every
 * relaxation here is about *how the same string is written*, never about *whether it
 * is the same string*, because a heading that genuinely differs is a real chapter
 * title and hiding it loses it.
 */
object ChapterHeading {

    /**
     * How many leading blocks may be searched for the chapter's own heading.
     *
     * Bounded for two reasons. A title that is not within the first handful of blocks
     * is not the chapter's opening, it is a subheading partway through — and hiding
     * the header for it would lose the real title. And this is read on every
     * recomposition, so an unbounded scan of a chapter with no lettered blocks at all
     * (a gallery of images) would walk it once per frame.
     */
    private const val MAX_LEADING_BLOCKS = 8

    /**
     * Whether the chapter's own opening already says everything the header would.
     *
     * @param label what the header's small line reads — "Chapter 3". It only decides
     *   anything when the chapter has no title of its own, because that is the only
     *   case where the label is all the header would say. When there *is* a title,
     *   matching the label alone must not hide the header: that would throw the
     *   title away to avoid repeating a number.
     */
    fun repeatsHeader(blockTexts: List<String>, title: String?, label: String): Boolean {
        val opening = openingWords(blockTexts) ?: return false
        val open = skeleton(opening)
        if (open.isEmpty()) return false

        val titled = skeleton(title.orEmpty())
        if (titled.isNotEmpty()) return open == titled

        val labelled = skeleton(label)
        return labelled.isNotEmpty() && open == labelled
    }

    /**
     * The first of the chapter's leading blocks that carries a letter.
     *
     * A letter rather than any character, because the blocks being stepped over are
     * precisely the wordless ones: a page break's empty string, an uncaptioned image,
     * and the stray running page number — "12" — that a reflowed PDF leaves at the
     * top of a chapter.
     */
    internal fun openingWords(blockTexts: List<String>): String? {
        val limit = minOf(MAX_LEADING_BLOCKS, blockTexts.size)
        for (index in 0 until limit) {
            val text = blockTexts[index]
            if (text.any(Char::isLetter)) return text
        }
        return null
    }

    /**
     * A string reduced to what a reader would say it was.
     *
     * Letters and digits, lowercased, everything else dropped. That covers the whole
     * family of invisible differences in one rule instead of a list of them: spaces
     * of every kind including the non-breaking one Java does not call whitespace,
     * zero-width joiners and byte-order marks, soft hyphens, and the punctuation a
     * heading carries that a table of contents does not.
     */
    internal fun skeleton(text: String): String = buildString(text.length) {
        for (char in text) if (char.isLetterOrDigit()) append(char.lowercaseChar())
    }
}
