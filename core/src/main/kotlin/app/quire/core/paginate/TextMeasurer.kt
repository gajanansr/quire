package app.quire.core.paginate

import app.quire.core.model.ContentBlock

/** How a block is set: enough for the measurer to lay it out. */
data class BlockStyle(
    val fontSizeSp: Float,
    val lineHeightPx: Float,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /**
     * Whether this block will be justified when it is drawn.
     *
     * Load-bearing, and easy to leave out. Justification changes where lines break,
     * so measuring without it and rendering with it produces two different line
     * counts for the same text — and the page is laid out for the smaller one, which
     * clips the last line off the bottom. The measurer must be told everything the
     * renderer will do.
     */
    val justify: Boolean = false,
    /**
     * Horizontal space this block gives up, in device pixels.
     *
     * A blockquote is set in from the margin, so it has a narrower measure and takes
     * more lines than the same words in body copy. Measuring it at full width and
     * drawing it indented is another way to lose the bottom of a page.
     */
    val indentPx: Float = 0f,
    /**
     * How far this block's first line is set in, in device pixels.
     *
     * The mark that separates one paragraph from the next in a book. It changes
     * where the first line breaks, so the measurer has to know it or the page is
     * laid out for a paragraph that is one line shorter than the one drawn.
     */
    val firstLineIndentPx: Float = 0f,
    /**
     * Whether this block opens a chapter and takes a raised initial.
     *
     * A larger opening letter makes the first line taller and changes where it
     * breaks, so it belongs to the style both sides read rather than to the drawing
     * alone.
     */
    val openingInitial: Boolean = false,
)

/**
 * The result of laying out one block at a given width.
 *
 * [lineEnds] holds the character offset just past the end of each line, which is
 * what lets the paginator split a paragraph without ever cutting inside a line.
 */
data class Measured(
    val heightPx: Float,
    val lineEnds: List<Int>,
) {
    val lineCount: Int get() = lineEnds.size
}

/**
 * Lays out text.
 *
 * `:core` declares this so the pagination algorithm can be tested with a
 * deterministic fake, exactly as `PdfTextSource` is. `:app`
 * implements it with Compose's own `TextMeasurer`.
 *
 * Pagination is the one part of the Reader where an off-by-one silently loses a
 * sentence, so it is worth being able to test at unit speed against known metrics
 * rather than only by reading pages on a device.
 */
interface TextMeasurer {
    fun measure(text: String, style: BlockStyle, widthPx: Float): Measured
}

data class Viewport(val widthPx: Float, val heightPx: Float)

data class TypographySettings(
    val fontSizeSp: Float = 19f,
    val lineHeightMultiple: Float = 1.55f,
    val fontKey: String = "serif",
    val justify: Boolean = false,
    /**
     * Device pixels per scale-independent pixel.
     *
     * Load-bearing, and easy to omit. The viewport arrives in device pixels while
     * type is specified in sp; without this factor the paginator believes lines are
     * (on a 2.75x screen) nearly three times shorter than they render, and packs
     * far more onto a page than fits. The symptom is a clipped last line, which
     * reads as text going missing rather than as a unit mismatch.
     */
    val pixelsPerSp: Float = 1f,
) {
    val bodyLineHeightPx: Float get() = fontSizeSp * lineHeightMultiple * pixelsPerSp
}

/**
 * How a block is set, for whoever is asking.
 *
 * Pagination and drawing both need this and must not each have an opinion. They did:
 * a level-one heading was measured at 1.6x the body size and drawn at 1.4x, and a
 * blockquote was measured upright at full width and drawn italic and indented. Every
 * one of those disagreements ends the same way — the page is laid out for fewer
 * lines than get drawn, and the surplus is clipped off the bottom with no error
 * anywhere.
 */
object BlockStyles {

    /** Headings are set larger than body text, by level. */
    private val HEADING_SCALE = mapOf(1 to 1.6f, 2 to 1.4f, 3 to 1.2f)
    private const val OTHER_HEADING_SCALE = 1.1f

    /** How far a quotation is set in from the margin, as a multiple of type size. */
    private const val QUOTE_INDENT_RATIO = 0.8f

    /**
     * Space between paragraphs, as a multiple of line height.
     *
     * Zero, because paragraphs are separated by an indent instead. A book does not
     * do both: the gap is the web's convention and the indent is the book's, and
     * using each for what it is for is most of what makes a page read as printed.
     */
    internal val paragraphSpacing = 0f

    /** How far a paragraph's first line is set in, as a multiple of type size. */
    private const val FIRST_LINE_INDENT_RATIO = 1.2f

    /** Extra space above a heading. */
    internal val headingSpacingAbove = 1.4f

    /** And a little beneath it, before the text it introduces. */
    internal val headingSpacingBelow = 0.3f

    fun of(block: ContentBlock, settings: TypographySettings): BlockStyle = when (block) {
        is ContentBlock.Heading -> {
            val scale = HEADING_SCALE[block.level] ?: OTHER_HEADING_SCALE
            BlockStyle(
                fontSizeSp = settings.fontSizeSp * scale,
                lineHeightPx = settings.bodyLineHeightPx * scale,
                bold = true,
                // A heading is never justified, whatever the body is set to.
                justify = false,
            )
        }

        is ContentBlock.BlockQuote -> BlockStyle(
            fontSizeSp = settings.fontSizeSp,
            lineHeightPx = settings.bodyLineHeightPx,
            italic = true,
            justify = settings.justify,
            indentPx = settings.fontSizeSp * QUOTE_INDENT_RATIO * settings.pixelsPerSp,
        )

        else -> BlockStyle(
            fontSizeSp = settings.fontSizeSp,
            lineHeightPx = settings.bodyLineHeightPx,
            justify = settings.justify,
        )
    }

    /** The indent a paragraph's opening line takes, when it takes one. */
    fun firstLineIndentPx(settings: TypographySettings): Float =
        settings.fontSizeSp * FIRST_LINE_INDENT_RATIO * settings.pixelsPerSp
}

/**
 * Whether a paragraph opens with an indent.
 *
 * Four cases do not, and they are the rule rather than exceptions to it: a
 * paragraph that opens a chapter, one that follows a heading, one that follows a
 * scene break, and one continued from the previous page. In every case the reader
 * already knows a new paragraph has begun — from the space above it, or from having
 * just turned the page — and an indent there reads as an error.
 *
 * Asked rather than decided, because pagination and drawing must agree: an indent
 * applied on one side only is a line's worth of width the page was not laid out for.
 */
object Indentation {

    fun shouldIndent(blocks: List<ContentBlock>, index: Int, startChar: Int): Boolean {
        val block = blocks.getOrNull(index) ?: return false
        if (block !is ContentBlock.Paragraph) return false
        // Continued from the previous page: the sentence is already in progress.
        if (startChar > 0) return false
        return when (blocks.getOrNull(index - 1)) {
            null -> false
            is ContentBlock.Heading -> false
            is ContentBlock.PageBreak -> false
            else -> true
        }
    }
}

/**
 * Space above a block, in device pixels.
 *
 * Above rather than below, and nothing at all above the first block on a page: a
 * page must begin at its top margin, and space budgeted after the last block is
 * space the page does not have. Rendering a fixed gap after every block instead —
 * including the last — is how a page came to be laid out for less than it drew, and
 * clipped the bottom line.
 */
fun spacingAbovePx(
    block: ContentBlock,
    settings: TypographySettings,
    isFirstOnPage: Boolean,
): Float {
    if (isFirstOnPage) return 0f
    return when (block) {
        is ContentBlock.Heading -> settings.bodyLineHeightPx * BlockStyles.headingSpacingAbove
        else -> settings.bodyLineHeightPx * BlockStyles.paragraphSpacing
    }
}

/** Space beneath a heading, before the text it introduces. */
fun trailingSpacingPx(block: ContentBlock, settings: TypographySettings): Float =
    when (block) {
        is ContentBlock.Heading -> settings.bodyLineHeightPx * BlockStyles.headingSpacingBelow
        else -> 0f
    }

/**
 * Where a chapter begins, and how it announces itself.
 *
 * Two conventions, both older than the screen. A chapter opens low on the page
 * rather than at the top margin — the "sink" — which is what tells a reader at a
 * glance that something has ended and something else has started. And its first
 * letter is set large.
 *
 * The sink is a proportion of the page, not a fixed measurement: the same gap that
 * looks generous on a phone is a rounding error on a tablet.
 */
object ChapterOpening {

    /** How far down the page a chapter starts, as a fraction of its height. */
    private const val SINK_RATIO = 0.12f

    /** How much larger the opening letter is set than the text it opens. */
    const val INITIAL_SCALE = 2.4f

    fun sinkPx(viewportHeightPx: Float): Float = viewportHeightPx * SINK_RATIO

    /**
     * Which block a chapter's prose begins at, or -1 if none does.
     *
     * The first paragraph, not the first block: a chapter usually begins with its
     * own heading, and the letter to set large is the one that starts the prose. A
     * first paragraph with nothing in it opens nothing, and nothing later takes its
     * place — a chapter has one opening or none.
     *
     * Stops at the first paragraph rather than walking the chapter, and asks the
     * spans whether they are blank rather than joining them into a string to ask.
     * Both matter: this used to be `blocks.take(index).none { it is Paragraph }`
     * called once per block, which read each block N/2 times — 2,005 reads per block
     * on a four-thousand-block chapter, the substring bug of 2026-09-12 in a new
     * place — and it rebuilt the paragraph's whole text every time, on the render
     * path, per frame, which is the allocation `Chapter.blockTexts` exists to avoid.
     */
    fun openingIndex(blocks: List<ContentBlock>): Int {
        val index = blocks.indexOfFirst { it is ContentBlock.Paragraph }
        if (index < 0) return -1
        val paragraph = blocks[index] as ContentBlock.Paragraph
        val hasWords = paragraph.spans.any { span ->
            span.text.any { !it.isWhitespace() }
        }
        return if (hasWords) index else -1
    }

    /**
     * Whether this block is the one a chapter opens with.
     *
     * Never a continuation — a paragraph carried over from the previous page has
     * already begun, and a raised initial mid-sentence is nonsense.
     */
    fun isChapterOpening(
        blocks: List<ContentBlock>,
        index: Int,
        startChar: Int,
    ): Boolean = opensChapter(openingIndex(blocks), index, startChar)

    /**
     * The same question, asked against an [openingIndex] worked out in advance.
     *
     * For callers in a loop over every block of a chapter, so the answer costs one
     * comparison rather than a fresh search.
     */
    fun opensChapter(openingIndex: Int, index: Int, startChar: Int): Boolean =
        startChar == 0 && openingIndex >= 0 && index == openingIndex
}

/**
 * How wide a column of text should be set.
 *
 * Past about 66 characters the eye loses its way back to the start of the next
 * line; the measure is the oldest setting in typography and the one readers feel
 * without naming.
 *
 * On a phone this never binds, and it is worth being clear about that rather than
 * claiming a fix that does nothing: a 19sp face in a 360dp column already sets
 * around 40 characters, comfortably inside the limit. It binds on a tablet, in
 * landscape, and on a foldable opened flat — where the full width would otherwise
 * give a hundred characters to a line and make the page genuinely hard to read.
 */
object Measure {

    /** Characters per line beyond which a measure is too wide to track. */
    private const val MAX_CHARACTERS = 66

    /** Average character width as a fraction of type size, for a serif text face. */
    private const val AVERAGE_CHAR_EM = 0.5f

    fun widthPx(availablePx: Float, settings: TypographySettings): Float {
        if (availablePx <= 0f) return availablePx
        val maxPx = MAX_CHARACTERS * AVERAGE_CHAR_EM * settings.fontSizeSp * settings.pixelsPerSp
        return minOf(availablePx, maxPx)
    }
}
