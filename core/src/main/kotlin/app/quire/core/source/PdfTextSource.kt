package app.quire.core.source

import app.quire.core.metadata.DocumentInfo

/**
 * A single positioned run of text on a page, as extracted from the PDF's content
 * stream. Position and font metrics are what make conservative reflow possible:
 * columns are found by clustering x, running headers by recurrence at a similar y,
 * and headings by font size relative to the body median.
 *
 * Coordinates are in PDF points with the origin at the page's bottom-left, matching
 * both Apache PDFBox and PdfBox-Android.
 */
data class TextRun(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fontSize: Float,
    val fontName: String,
    val bold: Boolean,
    val italic: Boolean,
    /**
     * Which line of the page this run belongs to, as the PDF engine grouped it.
     *
     * PDF content streams carry no notion of a line, but a mature engine works one
     * out — from the text matrix, the drop threshold and the font's own metrics —
     * and does it far better than clustering baselines afterwards, which is what
     * this project used to do. Superscripts, kerning, inline font changes and
     * multi-column reading order are all decided there.
     *
     * -1 when the source cannot say, in which case the baselines are clustered as
     * before. That is a real fallback, not a formality: a source built from
     * synthetic runs has no engine behind it.
     */
    val lineIndex: Int = -1,
)

data class PageGeometry(
    val pageIndex: Int,
    val width: Float,
    val height: Float,
)

data class PdfPage(
    val geometry: PageGeometry,
    val runs: List<TextRun>,
) {
    val charCount: Int get() = runs.sumOf { it.text.length }
}

/** An entry in the PDF's outline (bookmarks). Authoritative for chapter detection. */
data class OutlineEntry(
    val title: String,
    val pageIndex: Int,
    val level: Int,
)

/**
 * Supplies positioned text from a PDF.
 *
 * `:core` declares this rather than depending on a PDF library directly. The app
 * implements it with PdfBox-Android; `:core`'s own tests implement it with Apache
 * PDFBox on the JVM. Because both emit the same shape, reflow and structure
 * detection are exercised against real PDFs under plain JUnit, with no emulator.
 */
interface PdfTextSource : AutoCloseable {
    fun pageCount(): Int
    fun page(index: Int): PdfPage
    fun outline(): List<OutlineEntry>
    /** True when the document is encrypted and text cannot be extracted. */
    fun isEncrypted(): Boolean

    /**
     * Headings the document declares in its structure tree.
     *
     * A tagged PDF carries a logical structure tree — `/H1` to `/H6` elements in
     * reading order, not scoped to pages, so a chapter spanning forty pages is one
     * contiguous subtree. Where it exists this is ground truth rather than
     * inference, and it outranks every other source.
     *
     * Empty for the untagged majority, which is the honest answer: an untagged PDF
     * records where ink goes and nothing in it says a line is a heading.
     */
    fun declaredHeadings(): List<OutlineEntry> = emptyList()

    /**
     * Destinations named by the book's own contents page.
     *
     * A hyperlinked table of contents is a declaration: each entry points at a real
     * destination the producer chose. Weaker than tags or bookmarks — the titles are
     * whatever the page says — but still something the document states rather than
     * something inferred from type size.
     */
    fun contentsLinks(): List<OutlineEntry> = emptyList()

    /**
     * What the document says it is called, from its info dictionary.
     *
     * Defaulted so a source that has no metadata — or a test fake — need not care.
     * The value is advisory: PDF Title fields are wrong often enough that
     * [app.quire.core.metadata.TitleResolver] validates before believing one.
     */
    fun documentInfo(): DocumentInfo? = null
}
