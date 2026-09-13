package app.quire.android.pdf

import app.quire.core.source.OutlineEntry
import app.quire.core.source.PageGeometry
import app.quire.core.source.PdfPage
import app.quire.core.source.PdfTextSource
import app.quire.core.source.TextRun
import app.quire.core.metadata.DocumentInfo
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

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

    /**
     * The document's info dictionary.
     *
     * Read straight and passed on unvalidated — deciding whether a Title is really a
     * title belongs in one place, and that place is TitleResolver, where it can be
     * tested without a PDF. Wrapped because a malformed dictionary should leave the
     * book nameless rather than unreadable.
     */
    override fun documentInfo(): DocumentInfo? {
        // XMP first. It is the modern half of PDF metadata and the half tools
        // actually maintain; the info dictionary is older, frequently stale, and
        // where "Microsoft Word - thesis.doc" tends to survive. Either can still be
        // wrong, which is why TitleResolver validates whatever comes back.
        val xmp = runCatching { xmpInfo() }.getOrNull()
        val legacy = runCatching {
            doc.documentInformation?.let {
                DocumentInfo(
                    title = it.title?.trim()?.takeIf { t -> t.isNotEmpty() },
                    author = it.author?.trim()?.takeIf { a -> a.isNotEmpty() },
                )
            }
        }.getOrNull()

        val title = xmp?.title ?: legacy?.title
        val author = xmp?.author ?: legacy?.author
        return if (title == null && author == null) null else DocumentInfo(title, author)
    }

    /**
     * Dublin Core title and creator from the document's XMP packet.
     *
     * Parsed as XML rather than with a dedicated XMP library: two fields do not
     * justify a dependency, and the shape is fixed — `dc:title` holds an `rdf:Alt`
     * of language alternatives, `dc:creator` an `rdf:Seq` of names. Matching on
     * local names keeps it working whatever prefixes a producer chose.
     */
    private fun xmpInfo(): DocumentInfo? {
        val packet = doc.documentCatalog?.metadata?.exportXMPMetadata() ?: return null
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(packet)

        fun firstValue(localName: String): String? {
            val nodes = document.getElementsByTagNameNS("*", localName)
            for (i in 0 until nodes.length) {
                val items = (nodes.item(i) as? Element)
                    ?.getElementsByTagNameNS("*", "li")
                val text = when {
                    items != null && items.length > 0 -> items.item(0)?.textContent
                    else -> nodes.item(i)?.textContent
                }
                text?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            return null
        }

        val title = firstValue("title")
        val author = firstValue("creator")
        return if (title == null && author == null) null else DocumentInfo(title, author)
    }

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
                lineIndex = line,
            )
        }
    }

    /**
     * Headings the document declares in its structure tree.
     *
     * A tagged PDF states its heading hierarchy outright — `/H1` to `/H6` elements
     * in reading order — which is the only way to know a line is a heading rather
     * than merely large. Academic output, anything produced for accessibility
     * compliance, and most modern InDesign and Word exports carry it.
     *
     * Only the boundary is taken from the tree, not the words. Recovering an
     * element's own text means resolving marked-content ids back to glyphs, which is
     * a great deal of machinery for a title the page already shows; the page a
     * chapter starts on is the part that must be exact, because that is what
     * navigation and progress are built on. The title comes from that page's
     * largest type, and a slightly wrong title is visible and harmless where a
     * wrong boundary is neither.
     *
     * Empty for the untagged majority, which is the honest answer rather than a
     * failure.
     */
    override fun declaredHeadings(): List<OutlineEntry> = runCatching {
        val root = doc.documentCatalog?.structureTreeRoot ?: return emptyList()
        val found = mutableListOf<OutlineEntry>()

        fun walk(node: Any?, depth: Int) {
            if (depth > MAX_STRUCTURE_DEPTH) return
            val element = node as? PDStructureElement ?: return
            val type = element.standardStructureType
            if (type != null && HEADING_TYPES.contains(type)) {
                val page = element.page?.let { doc.pages.indexOf(it) } ?: -1
                if (page >= 0) {
                    val level = type.removePrefix("H").toIntOrNull() ?: 1
                    found += OutlineEntry(titleOnPage(page) ?: type, page, level)
                }
            }
            element.kids?.forEach { walk(it, depth + 1) }
        }

        root.kids?.forEach { walk(it, 0) }
        // Only the shallowest level defines chapters; deeper ones are sections.
        val top = found.minOfOrNull { it.level } ?: return emptyList()
        found.filter { it.level == top }.distinctBy { it.pageIndex }
    }.getOrDefault(emptyList())

    /**
     * Destinations named by the book's own contents page.
     *
     * Weaker than tags or bookmarks, and still a declaration: every entry points at
     * a destination the producer chose. Only the opening pages are searched, because
     * a contents page is at the front and link annotations elsewhere are citations,
     * cross-references and footnote returns rather than structure.
     */
    override fun contentsLinks(): List<OutlineEntry> = runCatching {
        val entries = mutableListOf<OutlineEntry>()
        val limit = minOf(doc.numberOfPages, CONTENTS_SEARCH_PAGES)

        for (pageIndex in 0 until limit) {
            doc.getPage(pageIndex).annotations
                .filterIsInstance<PDAnnotationLink>()
                .forEach { link ->
                    val target = destinationPageOf(link) ?: return@forEach
                    // A contents entry points forward, into the book.
                    if (target <= pageIndex) return@forEach
                    entries += OutlineEntry(titleOnPage(target) ?: "", target, 0)
                }
            if (entries.size >= MIN_CONTENTS_ENTRIES) break
        }
        if (entries.size < MIN_CONTENTS_ENTRIES) emptyList()
        else entries.distinctBy { it.pageIndex }.sortedBy { it.pageIndex }
    }.getOrDefault(emptyList())

    private fun destinationPageOf(link: PDAnnotationLink): Int? = runCatching {
        val destination = link.destination
            ?: (link.action as? PDActionGoTo)?.destination
        val page = (destination as? PDPageDestination)?.page ?: return null
        doc.pages.indexOf(page).takeIf { it >= 0 }
    }.getOrNull()

    /**
     * The largest type on a page, which on a chapter opening is its title.
     *
     * Only the topmost line of it. Two sections can begin on one page — an
     * acknowledgements note followed by a prologue — and joining every large run
     * there produced the single entry "Acknowledgements Prologue", which names
     * neither of them.
     */
    private fun titleOnPage(pageIndex: Int): String? = runCatching {
        val runs = page(pageIndex).runs
        if (runs.isEmpty()) return null

        val largest = runs.maxOf { it.fontSize }
        val biggest = runs.filter { it.fontSize >= largest - SIZE_TOLERANCE }
        val topmost = biggest.maxOf { it.y }

        biggest.filter { it.y >= topmost - largest * SAME_LINE_RATIO }
            .sortedBy { it.x }
            .joinToString(" ") { it.text.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotEmpty() && it.length <= MAX_TITLE_CHARS }
    }.getOrNull()

    private companion object {
        val HEADING_TYPES = setOf("H1", "H2", "H3", "H4", "H5", "H6")

        /** A malformed tree can be cyclic; depth bounds the walk. */
        const val MAX_STRUCTURE_DEPTH = 32

        /** A contents page is at the front; links deeper in are cross-references. */
        const val CONTENTS_SEARCH_PAGES = 12

        /** Fewer links than this is a cover link, not a contents page. */
        const val MIN_CONTENTS_ENTRIES = 3

        const val MAX_TITLE_CHARS = 90

        /** Font sizes within this many points count as the same size. */
        const val SIZE_TOLERANCE = 0.5f

        /** Baselines within this fraction of the type size are the same line. */
        const val SAME_LINE_RATIO = 0.6f
    }
}