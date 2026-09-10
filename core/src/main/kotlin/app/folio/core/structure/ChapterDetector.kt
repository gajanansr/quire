package app.folio.core.structure

import app.folio.core.model.Chapter
import app.folio.core.model.ContentBlock
import app.folio.core.model.plainText
import app.folio.core.source.OutlineEntry

/**
 * Divides a book's blocks into chapters.
 *
 * Sources of truth, in order:
 *
 *  1. **The PDF outline.** If the producer wrote bookmarks, they are the author's own
 *     structure and no heuristic should second-guess them.
 *  2. **Typographic headings**, already identified by
 *     [app.folio.core.reflow.ParagraphAssembler] from real font metrics.
 *  3. **Naming patterns** — "Chapter 4", "Part II", "Prologue" — for books whose
 *     openings are set in body type.
 *
 * When nothing scores confidently the whole book becomes one chapter. That is the
 * deliberate outcome, not a failure: a wrong boundary corrupts navigation and
 * progress permanently, while one long chapter is merely plain (spec section 7).
 *
 * A single candidate in a long book is also rejected. One heading is a section
 * marker or a note on sources; a chapter scheme repeats.
 */
class ChapterDetector {

    private companion object {
        /** A candidate must score at least this to be believed. */
        const val MIN_STRENGTH = 0.6
        /** Fewer candidates than this is not a chapter scheme. */
        const val MIN_CANDIDATES = 2
    }

    fun detect(
        blocks: List<ContentBlock>,
        outline: List<OutlineEntry> = emptyList(),
        pageBreaks: List<Int> = emptyList(),
    ): List<Chapter> {
        if (blocks.isEmpty()) return emptyList()

        fromOutline(blocks, outline, pageBreaks)?.let { return it }
        fromHeadings(blocks)?.let { return it }
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

    private fun fromHeadings(blocks: List<ContentBlock>): List<Chapter>? {
        val scored = blocks.mapIndexedNotNull { i, block ->
            HeadingSignals.match(block)
                ?.takeIf { it.strength >= MIN_STRENGTH }
                ?.let { Candidate(i, it.title, it.strength) }
        }
        val candidates = collapseAdjacent(scored)
        if (candidates.size < MIN_CANDIDATES) return null
        return build(blocks, candidates)
    }

    private data class Candidate(val index: Int, val title: String, val strength: Double)

    /**
     * Merges consecutive candidates into one boundary.
     *
     * Books very often set an opening as two lines — a label ("Chapter 1") above a
     * title ("The Weight of Silence"). Both match, but they mark one chapter, not
     * two. The run starts at the label and takes its name from the strongest member,
     * which is the typographic title rather than the label.
     */
    private fun collapseAdjacent(scored: List<Candidate>): List<Pair<Int, String>> {
        if (scored.isEmpty()) return emptyList()

        val runs = mutableListOf<MutableList<Candidate>>()
        scored.forEach { c ->
            val open = runs.lastOrNull()
            if (open != null && c.index == open.last().index + 1) open += c
            else runs += mutableListOf(c)
        }

        return runs.map { run ->
            val best = run.maxByOrNull { it.strength } ?: run.first()
            run.first().index to best.title
        }
    }

    /** Cuts [blocks] at the given start indices, keeping any front matter intact. */
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
