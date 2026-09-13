package app.quire.core.fixtures

import app.quire.core.source.OutlineEntry
import app.quire.core.source.PageGeometry
import app.quire.core.source.PdfPage
import app.quire.core.source.PdfTextSource
import app.quire.core.source.TextRun
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File

/**
 * JVM implementation of [PdfTextSource] backed by Apache PDFBox, used only by tests.
 *
 * The Android app implements the same interface with PdfBox-Android, which is a port
 * of Apache PDFBox 2.x — hence the 2.0.37 pin. Keeping both on the same API means the
 * reflow and structure logic exercised here is the logic that runs on device.
 */
class PdfBoxTextSource(file: File) : PdfTextSource {

    private val doc: PDDocument = PDDocument.load(file)

    override fun pageCount(): Int = doc.numberOfPages

    override fun isEncrypted(): Boolean = doc.isEncrypted

    override fun page(index: Int): PdfPage {
        val pdPage = doc.getPage(index)
        val box = pdPage.mediaBox
        val collector = RunCollector().apply {
            startPage = index + 1
            endPage = index + 1
            sortByPosition = true
            getText(doc)   // drives writeString; output is discarded
        }
        return PdfPage(
            geometry = PageGeometry(index, box.width, box.height),
            runs = collector.runs,
        )
    }

    override fun outline(): List<OutlineEntry> {
        val root = doc.documentCatalog?.documentOutline ?: return emptyList()
        val out = mutableListOf<OutlineEntry>()
        fun walk(item: PDOutlineItem?, level: Int) {
            var node = item
            while (node != null) {
                val title = node.title?.trim()
                val pageIndex = runCatching {
                    node!!.findDestinationPage(doc)?.let { doc.pages.indexOf(it) }
                }.getOrNull() ?: -1
                if (!title.isNullOrEmpty() && pageIndex >= 0) {
                    out += OutlineEntry(title, pageIndex, level)
                }
                walk(node.firstChild, level + 1)
                node = node.nextSibling
            }
        }
        walk(root.firstChild, 0)
        return out
    }

    override fun close() = doc.close()

    /**
     * Harvests [TextPosition] data, which carries per-glyph coordinates and font
     * metrics. PDFBox hands us one call per visual string; we merge the glyphs of
     * each call into a single run and take the median font size, so a stray glyph
     * cannot skew a run's reported size.
     */
    private class RunCollector : PDFTextStripper() {
        val runs = mutableListOf<TextRun>()

        /** Incremented by the engine between lines; see TextRun.lineIndex. */
        private var line = 0

        override fun writeLineSeparator() {
            line++
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>?) {
            val positions = textPositions?.filter { it.unicode.isNotEmpty() }
            if (positions.isNullOrEmpty() || text.isBlank()) return

            val first = positions.first()
            val minX = positions.minOf { it.xDirAdj }
            val maxX = positions.maxOf { it.xDirAdj + it.widthDirAdj }
            val sizes = positions.map { it.fontSizeInPt }.sorted()
            val fontName = first.font?.name.orEmpty()

            runs += TextRun(
                text = text,
                x = minX,
                // yDirAdj grows downward; convert to a bottom-left origin.
                y = first.pageHeight - first.yDirAdj,
                width = maxX - minX,
                height = positions.maxOf { it.heightDir },
                fontSize = sizes[sizes.size / 2],
                fontName = fontName,
                bold = fontName.contains("Bold", ignoreCase = true) ||
                    fontName.contains("Black", ignoreCase = true) ||
                    fontName.contains("Heavy", ignoreCase = true),
                italic = fontName.contains("Italic", ignoreCase = true) ||
                    fontName.contains("Oblique", ignoreCase = true),
                lineIndex = line,
            )
        }
    }
}
