package app.quire.core.paginate

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.model.plainText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chapter that printed "Part 1" twice.
 *
 * Every fixture here is a shape a real book produces, not an invented one: the
 * page break an EPUB puts before a chapter's heading, the running page number a
 * reflowed PDF leaves above it, the title set as a styled paragraph rather than an
 * `<h1>`, and `Part&nbsp;1` written with the space that does not break.
 *
 * The negative cases carry the weight. A heading that genuinely differs from the
 * title is a real chapter title, and hiding its header loses it — so a subtitle, a
 * differing heading, and a first paragraph that merely begins with the title all
 * have to keep the header.
 */
class ChapterHeadingTest {

    private val prose =
        "The rain had not stopped for three days, and the river was running high."

    private fun heading(text: String) = ContentBlock.Heading(1, listOf(InlineSpan(text)))
    private fun para(text: String) = ContentBlock.Paragraph(listOf(InlineSpan(text)))

    private fun chapter(title: String?, vararg blocks: ContentBlock) = Chapter(
        index = 0, title = title, blocks = blocks.toList(),
        startCharOffset = 0, charCount = blocks.sumOf { it.plainText.length },
    )

    /** The Reader's own question, asked the way the Reader asks it. */
    private fun repeats(chapter: Chapter, label: String = "Chapter 1") =
        ChapterHeading.repeatsHeader(chapter.blockTexts, chapter.title, label)

    // ------------------------------------------------------- the reported failure

    @Test
    fun `a heading that is the title is not printed twice`() {
        val ch = chapter("Part 1", heading("Part 1"), para(prose))
        assertTrue(repeats(ch), "the header would have drawn Part 1 above Part 1")
    }

    @Test
    fun `a page break before the heading does not hide it`() {
        // The shape that broke the old rule on a real book: it asked blocks.first(),
        // which was the break, and concluded the chapter had no heading of its own.
        val ch = chapter(
            "Part 1", ContentBlock.PageBreak(sourcePage = 41), heading("Part 1"), para(prose),
        )
        assertTrue(repeats(ch), "a page break ahead of the heading brought the title back")
    }

    @Test
    fun `an empty block before the heading does not hide it`() {
        // An anchor or a styling span that reflowed to nothing still occupies a slot.
        val ch = chapter("Part 1", para("   "), heading("Part 1"), para(prose))
        assertTrue(repeats(ch), "an empty leading block brought the title back")
    }

    @Test
    fun `a running page number before the heading does not hide it`() {
        // A reflowed PDF lifts the page number off the top of the page as its own
        // block. It has no letters, so it is not the chapter's opening words.
        val ch = chapter("Part 1", para("12"), heading("Part 1"), para(prose))
        assertTrue(repeats(ch), "a stray page number brought the title back")
    }

    @Test
    fun `a title set as a paragraph counts as the heading`() {
        // Plenty of books style the chapter title with a class rather than an h1.
        val ch = chapter("Part 1", para("Part 1"), para(prose))
        assertTrue(repeats(ch), "a title set as a paragraph was printed twice")
    }

    // ------------------------------------------------- differences a reader cannot see

    @Test
    fun `a non-breaking space is the same space`() {
        // Part&nbsp;1. Java does not call U+00A0 whitespace, so trim and equals both
        // said these were different strings.
        val ch = chapter("Part 1", heading("Part 1"), para(prose))
        assertTrue(repeats(ch), "a non-breaking space brought the title back")
    }

    @Test
    fun `trailing punctuation is not a difference`() {
        assertTrue(repeats(chapter("Part 1", heading("Part 1."), para(prose))))
        assertTrue(repeats(chapter("Part 1", heading("PART 1:"), para(prose))))
        assertTrue(repeats(chapter("Part 1", heading("— Part 1 —"), para(prose))))
    }

    @Test
    fun `a soft hyphen or a zero-width space is not a difference`() {
        val ch = chapter("Part 1", heading("Par­t​ 1"), para(prose))
        assertTrue(repeats(ch), "an invisible character brought the title back")
    }

    @Test
    fun `a doubled space is not a difference`() {
        val ch = chapter("The Long Way Home", heading("The  Long\nWay Home"), para(prose))
        assertTrue(repeats(ch), "a line break inside the tag brought the title back")
    }

    @Test
    fun `case is not a difference`() {
        assertTrue(repeats(chapter("The Fall", heading("THE FALL"), para(prose))))
    }

    // ------------------------------------------- a chapter with no title of its own

    @Test
    fun `an untitled chapter whose heading is the label is not printed twice`() {
        // Nothing in the table of contents, so the header can only say "Chapter 3" —
        // and the chapter's own heading says exactly that.
        val ch = chapter(null, heading("Chapter 3"), para(prose))
        assertTrue(repeats(ch, label = "Chapter 3"), "Chapter 3 was printed twice")
    }

    @Test
    fun `an untitled chapter whose heading differs keeps its header`() {
        val ch = chapter(null, heading("The Fall"), para(prose))
        assertFalse(repeats(ch, label = "Chapter 3"))
    }

    // ------------------------------------------------------------- what must survive

    @Test
    fun `a heading that differs from the title keeps its header`() {
        // The whole risk of this change. "The Fall" is the chapter's own heading and
        // "Part 1" is what the table of contents calls it; both are real and both
        // belong on the page.
        val ch = chapter("Part 1", heading("The Fall"), para(prose))
        assertFalse(repeats(ch), "a genuinely different heading lost its header")
    }

    @Test
    fun `a subtitle is a difference`() {
        val ch = chapter("Part 1", heading("Part 1: The Fall"), para(prose))
        assertFalse(repeats(ch), "the header was hidden and the subtitle was all that said Part 1")
    }

    @Test
    fun `a title the chapter merely begins with keeps its header`() {
        val ch = chapter("Rain", para("Rain had not stopped for three days."), para(prose))
        assertFalse(repeats(ch), "a paragraph starting with the title hid the header")
    }

    @Test
    fun `a titled chapter whose heading is only the label keeps its header`() {
        // "Chapter 3" above "The Fall" is a repeat of the label, not of the title.
        // Hiding the header to avoid it would throw the title away entirely.
        val ch = chapter("The Fall", heading("Chapter 3"), para(prose))
        assertFalse(repeats(ch, label = "Chapter 3"), "the chapter's title was lost")
    }

    @Test
    fun `a chapter with no words keeps its header`() {
        val ch = chapter(
            "Plates",
            ContentBlock.Image(path = "p1.jpg"),
            ContentBlock.PageBreak(sourcePage = 3),
        )
        assertFalse(repeats(ch))
    }

    @Test
    fun `a chapter with no blocks keeps its header`() {
        assertFalse(repeats(chapter("Part 1")))
    }

    @Test
    fun `a heading buried past the opening is not the chapter's own`() {
        // A subheading partway down is not what the header repeats, and treating it
        // as one would hide a real title.
        val blocks: List<ContentBlock> = List(9) { para(prose) } + heading("Part 1")
        assertFalse(repeats(chapter("Part 1", *blocks.toTypedArray())))
    }

    // --------------------------------------------------------------- the comparison

    @Test
    fun `only letters and digits count, on either side`() {
        assertTrue(ChapterHeading.sameWords("  PART — 1. ", "part1"))
        assertTrue(ChapterHeading.sameWords("— ‘’ …", ""))
        assertFalse(ChapterHeading.sameWords("Part 1", "Part 2"))
        assertFalse(ChapterHeading.sameWords("Part 1", "Part 1 and a half"))
        assertFalse(ChapterHeading.sameWords("Part 1 and a half", "Part 1"))
    }

    @Test
    fun `comparing stops at the first difference rather than copying the block`() {
        // The chapter's opening block can be the whole book, and this is asked on
        // every recomposition. Reducing both sides to strings before comparing would
        // allocate a copy of it per frame — the `plainText` mistake that
        // `Chapter.blockTexts` exists to prevent. A megabyte of prose against a short
        // title must cost the title, not the megabyte.
        val whole = "Rain had not stopped for three days. ".repeat(30_000)
        assertFalse(ChapterHeading.sameWords(whole, "Part 1"))
    }

}
