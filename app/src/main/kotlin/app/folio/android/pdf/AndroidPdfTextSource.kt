package app.folio.android.pdf

import app.folio.core.source.OutlineEntry
import app.folio.core.source.PageGeometry
import app.folio.core.source.PdfPage
import app.folio.core.source.PdfTextSource
import app.folio.core.source.TextRun
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * Android implementation of [PdfTextSource], backed by PdfBox-Android.
 *
 * `:core`'s tests implement the same interface against Apache PDFBox. PdfBox-Android
 * is a port of PDFBox 2.x, so the two share an API and this class is deliberately a
 * near-mirror of that one: any divergence between them is a bug, because the reflow
 * logic proven against the JVM version is the logic that must run here.
 *
 * Requires `PDFBoxResourceLoader.init(context)` once before use, for font resources.
 */
class AndroidPdfTextSource(file: File) : PdfTextSource {

    private val doc: PDDocument = PDDocument.load(file)

    override fun pageCount(): Int = doc.numberOfPages

    override fun isEncrypted(): Boolean = doc.isEncrypted

    override fun page(index: Int): PdfPage {
        val box = doc.getPage(index).mediaBox
        val collector = RunCollector().apply {
            startPage = index + 1
            endPage = index + 1
            sortByPosition = true
            getText(doc)
        }
        return PdfPage(PageGeometry(index, box.width, box.height), collector.runs)
    }

    override fun outline(): List<OutlineEntry> {
        val root = doc.documentCatalog?.documentOutline ?: return emptyList()
        val out = mutableListOf<OutlineEntry>()
        fun walk(item: PDOutlineItem?, level: Int) {
            var node = item
            while (node != null) {
                val current = node
                val title = current.title?.trim()
                val pageIndex = runCatching {
                    current.findDestinationPage(doc)?.let { doc.pages.indexOf(it) }
                }.getOrNull() ?: -1
                if (!title.isNullOrEmpty() && pageIndex >= 0) {
                    out += OutlineEntry(title, pageIndex, level)
                }
                walk(current.firstChild, level + 1)
                node = current.nextSibling
            }
        }
        walk(root.firstChild, 0)
        return out
    }

    override fun close() = doc.close()

    private class RunCollector : PDFTextStripper() {
        val runs = mutableListOf<TextRun>()

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
                // yDirAdj grows downward; convert to the bottom-left origin the
                // model uses so reflow sees the same space on both platforms.
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
            )
        }
    }
}
