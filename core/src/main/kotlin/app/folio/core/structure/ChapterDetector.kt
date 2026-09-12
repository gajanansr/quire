package app.folio.core.structure

import app.folio.core.model.Chapter
import app.folio.core.model.ContentBlock
import app.folio.core.model.plainText
import app.folio.core.source.OutlineEntry

/**
 * Divides a book's blocks into chapters.
 *
 * Only declared structure is believed. There is exactly one source — boundaries the
 * document itself states — and where a document states none, the book is one chapter.
 *
 * It used to guess. Typographic headings and "Chapter 4" naming patterns were scored,
 * and two candidates past a 0.6 threshold were enough to carve up a book. That is how
 * front matter, a dedication and a running head became chapters, and how a book with
 * one large line in the middle acquired a boundary nobody wrote.
 *
 * The reason to stop is not that the heuristics were weak but that the fact is often
 * absent. A PDF records where ink goes; nothing in an untagged file says "this line
 * is a heading". Inferring it is inventing it, and a wrong boundary is permanent and
 * silent: it corrupts navigation, it corrupts progress, and the reader has no way to
 * know it happened. One long chapter is merely plain, and the spec asks for exactly
 * this — preserve the content rather than invent the structure.
 *
 * Declared sources exist in more places than bookmarks: a tagged PDF's structure
 * tree and a hyperlinked contents page both state boundaries outright. Choosing
 * between them is [app.folio.core.pdf.PdfPipeline]'s job; believing what it passes
 * is this class's.
 *
 * A single entry is still rejected. One bookmark is a cover link or a note on
 * sources; a chapter scheme repeats.
 */
class ChapterDetector {

    private companion object {
        /** One entry is not a chapter scheme; a scheme repeats. */
        const val MIN_CANDIDATES = 2
    }

    fun detect(
        blocks: List<ContentBlock>,
        outline: List<OutlineEntry> = emptyList(),
        pageBreaks: List<Int> = emptyList(),
    ): List<Chapter> {
        if (blocks.isEmpty()) return emptyList()

        fromOutline(blocks, outline, pageBreaks)?.let { return it }
        return listOf(single(blocks))
    }

    /**
     * Splits on the document's own bookmarks. Needs [pageBreaks] to turn an outline
     * entry's page number into a block index.
     */
    private fun fromOutline(
        blocks: List<ContentBlock>,
        outline: List<OutlineEntry>,
        pageBreaks: List<Int>,
    ): List<Chapter>? {
        if (outline.size < MIN_CANDIDATES || pageBreaks.isEmpty()) return null

        // Only top-level entries define chapters; deeper levels are sections within.
        val topLevel = outline.filter { it.level == outline.minOf { e -> e.level } }
        if (topLevel.size < MIN_CANDIDATES) return null

        val starts = topLevel
            .mapNotNull { entry ->
                pageBreaks.getOrNull(entry.pageIndex)?.let { it to entry.title }
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
        if (starts.size < MIN_CANDIDATES) return null

        return build(blocks, starts)
    }

    private fun build(blocks: List<ContentBlock>, starts: List<Pair<Int, String>>): List<Chapter> {
        val cuts = starts.sortedBy { it.first }
        val chapters = mutableListOf<Chapter>()

        // Content before the first boundary is front matter and must not be dropped.
        if (cuts.first().first > 0) {
            chapters += Chapter(0, null, blocks.subList(0, cuts.first().first), 0, 0)
        }

        cuts.forEachIndexed { i, (start, title) ->
            val end = cuts.getOrNull(i + 1)?.first ?: blocks.size
            if (end > start) {
                chapters += Chapter(0, title.ifBlank { null }, blocks.subList(start, end), 0, 0)
            }
        }

        return chapters.renumbered()
    }

    private fun single(blocks: List<ContentBlock>) =
        Chapter(0, null, blocks, 0, blocks.sumOf { it.plainText.length })

    /** Assigns indices and cumulative character offsets. */
    private fun List<Chapter>.renumbered(): List<Chapter> {
        var offset = 0
        return mapIndexed { i, c ->
            val count = c.blocks.sumOf { it.plainText.length }
            Chapter(i, c.title, c.blocks, offset, count).also { offset += count }
        }
    }
}
