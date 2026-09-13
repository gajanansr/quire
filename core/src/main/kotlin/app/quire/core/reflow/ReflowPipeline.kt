package app.quire.core.reflow

import app.quire.core.QuireConstants
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.model.plainText
import app.quire.core.source.PdfPage
import app.quire.core.source.PdfTextSource

data class ReflowResult(
    val blocks: List<ContentBlock>,
    val confidence: Double,
    /** Index into [blocks] where each source page began. */
    val pageBreaks: List<Int>,
)

/**
 * Turns a PDF's positioned text into readable blocks.
 *
 * Order matters. Columns are detected first, from raw runs, because line assembly
 * merges across a gutter. Furniture is removed while page geometry is still known.
 * Hyphens are resolved before paragraphs form, so a split word never straddles a
 * block boundary. Paragraphs are assembled per page, because the y-coordinates that
 * drive gap detection reset at every page, and only then are pages stitched.
 *
 * When the result looks poor, the extracted text is still returned — with a low
 * confidence attached — rather than an empty document. A caller seeing confidence
 * below [QuireConstants.MIN_REFLOW_CONFIDENCE] should offer the original PDF, but
 * the words are never thrown away (spec section 6).
 */
class ReflowPipeline(
    private val columns: ColumnDetector = ColumnDetector(),
    private val lines: LineAssembler = LineAssembler(),
    private val furniture: HeaderFooterDetector = HeaderFooterDetector(),
    private val hyphens: Dehyphenator = Dehyphenator(),
    private val paragraphs: ParagraphAssembler = ParagraphAssembler(),
) {

    private companion object {
        /** A paragraph ending in one of these has finished; do not stitch onward. */
        const val TERMINAL = ".!?\"'”’)]»"
        /** Below this mean paragraph length, reflow has produced fragments. */
        const val HEALTHY_PARAGRAPH_CHARS = 80.0
        /** A document with less text than this per page is barely extractable. */
        const val MIN_CHARS_PER_PAGE = 20
    }

    fun reflow(source: PdfTextSource): ReflowResult {
        val pages = (0 until source.pageCount()).map { source.page(it) }
        if (pages.isEmpty()) return ReflowResult(emptyList(), 0.0, emptyList())

        val layout = columns.detect(pages)
        val perPageLines = pages.map { lines.assemble(it, layout) }
        val stripped = furniture.strip(perPageLines, pages.map { it.geometry.height })

        // Resolve hyphenation within each page, then across the page seam.
        val dehyphenated = stitchHyphens(stripped.pages.map { hyphens.join(it) })

        val perPageBlocks = dehyphenated.map { paragraphs.assemble(it) }
        val (blocks, pageBreaks) = stitchPages(perPageBlocks)

        val confidence = score(pages, stripped, blocks)

        return if (blocks.isEmpty() && pages.any { it.charCount > 0 }) {
            // Reflow collapsed but text exists: hand back the raw lines untouched.
            val raw = dehyphenated.flatten()
                .map { ContentBlock.Paragraph(listOf(InlineSpan(it.text))) }
            ReflowResult(raw, minOf(confidence, 0.2), pageBreaks)
        } else {
            ReflowResult(blocks, confidence, pageBreaks)
        }
    }

    /**
     * Joins a word broken across a page boundary. Handled here rather than in
     * [Dehyphenator] because the baseline gap across a seam is meaningless — the
     * last line of one page sits near y=0 and the first of the next near the top.
     */
    private fun stitchHyphens(pages: List<List<Line>>): List<List<Line>> {
        val out = pages.map { it.toMutableList() }
        for (i in 0 until out.size - 1) {
            val tail = out[i].lastOrNull() ?: continue
            val head = out[i + 1].firstOrNull() ?: continue

            val a = tail.text.trimEnd()
            val b = head.text.trimStart()
            if (a.isEmpty() || b.isEmpty()) continue
            if (a.last() != '-' || !b.first().isLetter()) continue

            val stem = if (b.first().isLowerCase()) a.dropLast(1) else a
            out[i][out[i].lastIndex] = tail.copy(text = stem + b)
            out[i + 1].removeAt(0)
        }
        return out
    }

    /**
     * Concatenates pages, merging a paragraph that continues across the seam.
     *
     * A paragraph is treated as continuing when it ends without terminal punctuation
     * and the next page opens with a lowercase paragraph. Both conditions are
     * required, so a chapter ending mid-clause is joined while two complete
     * sentences are left alone.
     */
    private fun stitchPages(perPage: List<List<ContentBlock>>): Pair<List<ContentBlock>, List<Int>> {
        val blocks = mutableListOf<ContentBlock>()
        val breaks = mutableListOf<Int>()

        perPage.forEach { page ->
            breaks += blocks.size

            val previous = blocks.lastOrNull()
            val incoming = page.firstOrNull()

            if (previous is ContentBlock.Paragraph && incoming is ContentBlock.Paragraph &&
                continues(previous.plainText, incoming.plainText)
            ) {
                blocks[blocks.lastIndex] = ContentBlock.Paragraph(
                    listOf(InlineSpan((previous.plainText.trimEnd() + " " +
                        incoming.plainText.trimStart()).trim()))
                )
                blocks += page.drop(1)
            } else {
                blocks += page
            }
        }
        return blocks to breaks
    }

    private fun continues(before: String, after: String): Boolean {
        val a = before.trimEnd()
        val b = after.trimStart()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.last() in TERMINAL) return false
        return b.first().isLowerCase()
    }

    /**
     * How much to trust the result.
     *
     * Blends three independent signals: whether furniture detection had clean
     * evidence, whether paragraphs came out of a readable length rather than as
     * fragments, and whether there was meaningful text to begin with. Poor scores
     * do not suppress output; they tell the caller to offer the original PDF.
     */
    private fun score(
        pages: List<PdfPage>,
        stripped: StripResult,
        blocks: List<ContentBlock>,
    ): Double {
        val totalChars = pages.sumOf { it.charCount }
        if (totalChars < pages.size * MIN_CHARS_PER_PAGE) return 0.0
        if (blocks.isEmpty()) return 0.0

        val paragraphs = blocks.filterIsInstance<ContentBlock.Paragraph>()
        if (paragraphs.isEmpty()) return 0.25

        val meanLength = paragraphs.sumOf { it.plainText.length }.toDouble() / paragraphs.size
        val lengthScore = (meanLength / HEALTHY_PARAGRAPH_CHARS).coerceIn(0.0, 1.0)

        // Reflow should not lose characters; a large shortfall means something ate text.
        val kept = blocks.sumOf { it.plainText.length }.toDouble()
        val retention = (kept / totalChars).coerceIn(0.0, 1.0)

        return (0.45 * lengthScore + 0.35 * retention + 0.20 * stripped.confidence)
            .coerceIn(0.0, 1.0)
    }
}
