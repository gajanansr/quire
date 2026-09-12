package app.folio.core.paginate

import app.folio.core.model.ContentBlock

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

    /** Space after a paragraph, as a multiple of its line height. */
    internal val paragraphSpacing = 0.55f

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
