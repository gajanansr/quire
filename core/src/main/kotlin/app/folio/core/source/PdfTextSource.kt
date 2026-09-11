package app.folio.core.source

import app.folio.core.metadata.DocumentInfo

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
     * What the document says it is called, from its info dictionary.
     *
     * Defaulted so a source that has no metadata — or a test fake — need not care.
     * The value is advisory: PDF Title fields are wrong often enough that
     * [app.folio.core.metadata.TitleResolver] validates before believing one.
     */
    fun documentInfo(): DocumentInfo? = null
}
