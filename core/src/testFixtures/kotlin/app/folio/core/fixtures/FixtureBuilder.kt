package app.folio.core.fixtures

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generates the test corpus. Reflow and structure detection are only as good as the
 * documents they are developed against, so these are built first and deliberately
 * include the awkward cases: two columns, running headers, hyphenated line breaks,
 * a scan with no text layer, a truncated file, and a file whose extension lies.
 *
 * Fixtures are cached on disk under core/src/test/resources/generated (gitignored)
 * and regenerated only when missing.
 */
object Fixtures {

    /**
     * Where generated fixtures live. Defaults to the module's test resources, but
     * instrumented tests override it via `folio.fixtures.dir` because an app on
     * device cannot write into the project tree.
     */
    /**
     * Bump whenever a fixture's *content* changes.
     *
     * Fixtures are cached by filename, so editing a builder without changing its
     * name leaves the old file on disk and every test keeps running against the
     * previous book. That failure is silent and reads as a bug in the code under
     * test — it has already cost time twice. The version is part of the path, so a
     * bump simply misses the cache and rebuilds.
     */
    private const val FIXTURE_VERSION = 3

    private val root: File by lazy {
        val override = System.getProperty("folio.fixtures.dir")
        File(override ?: "src/test/resources/generated/v$FIXTURE_VERSION").apply { mkdirs() }
    }

    private fun cached(name: String, build: (File) -> Unit): File {
        val f = File(root, name)
        if (!f.exists() || f.length() == 0L) build(f)
        return f
    }

    // ---------------------------------------------------------------- text

    private val LOREM = ("Distributed systems are a collection of independent computers " +
        "that appear to their users as a single coherent system. The consequences of " +
        "this definition are far reaching, and they shape every design decision that " +
        "follows in this book. ").repeat(3)

    fun plainTxt() = cached("plain.txt") { f ->
        f.writeText(
            buildString {
                appendLine("A History of Quiet Things")
                appendLine()
                appendLine("Chapter 1")
                appendLine()
                appendLine(LOREM)
                appendLine()
                appendLine("Chapter 2")
                appendLine()
                appendLine(LOREM)
            }
        )
    }

    // ---------------------------------------------------------------- EPUB

    private fun zip(target: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(target.outputStream().buffered()).use { zos ->
            entries.forEach { (path, bytes) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    private fun chapterXhtml(title: String, body: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>$title</title></head>
          <body>
            <h1>$title</h1>
            <p>$body</p>
            <p>A second paragraph with <em>emphasis</em> and <strong>strength</strong>.</p>
            <ul><li>First item</li><li>Second item</li></ul>
            <blockquote>A quoted line.</blockquote>
          </body>
        </html>
    """.trimIndent().toByteArray()

    private val CONTAINER_XML = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf"
            media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent().toByteArray()

    private fun opf(withNav: Boolean, withCover: Boolean = false) = """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="uid">urn:uuid:folio-test-0001</dc:identifier>
            <dc:title>A History of Quiet Things</dc:title>
            <dc:creator>Ada Marlowe</dc:creator>
            <dc:language>en</dc:language>
            <dc:publisher>Folio Test Press</dc:publisher>
            <dc:description>A study of the spaces between words, and what lives there.</dc:description>
            <dc:subject>Essay</dc:subject>
            <dc:subject>Design</dc:subject>
          </metadata>
          <manifest>
            <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
            ${if (withNav) """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" else ""}
            ${if (withCover) """<item id="ci" href="cover.png" media-type="image/png" properties="cover-image"/>""" else ""}
          </manifest>
          <spine>
            <itemref idref="c1"/>
            <itemref idref="c2"/>
          </spine>
        </package>
    """.trimIndent().toByteArray()

    /**
     * A small solid-colour PNG standing in for cover art.
     *
     * Real enough to decode: the importer writes whatever bytes it finds straight to
     * disk, and a test that passed on a byte array no decoder would accept would
     * prove nothing about the path that matters.
     */
    private fun coverPng(): ByteArray {
        val image = java.awt.image.BufferedImage(120, 180, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = java.awt.Color(0x2B, 0x33, 0x50)
        g.fillRect(0, 0, 120, 180)
        g.dispose()
        return java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(image, "png", it)
        }.toByteArray()
    }

    private val NAV_XHTML = """
        <?xml version="1.0" encoding="utf-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <body><nav epub:type="toc">
            <ol>
              <li><a href="c1.xhtml">The Weight of Silence</a></li>
              <li><a href="c2.xhtml">What the River Kept</a></li>
            </ol>
          </nav></body>
        </html>
    """.trimIndent().toByteArray()

    fun cleanEpub() = cached("clean.epub") { f ->
        zip(
            f,
            listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to CONTAINER_XML,
                "OEBPS/content.opf" to opf(withNav = true, withCover = true),
                "OEBPS/nav.xhtml" to NAV_XHTML,
                "OEBPS/cover.png" to coverPng(),
                "OEBPS/c1.xhtml" to chapterXhtml("The Weight of Silence", LOREM),
                "OEBPS/c2.xhtml" to chapterXhtml("What the River Kept", LOREM),
            )
        )
    }

    /** Spine only — no nav document and no NCX. Titles must come from headings. */
    fun epubNoNav() = cached("nonav.epub") { f ->
        zip(
            f,
            listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to CONTAINER_XML,
                "OEBPS/content.opf" to opf(withNav = false),
                "OEBPS/c1.xhtml" to chapterXhtml("The Weight of Silence", LOREM),
                "OEBPS/c2.xhtml" to chapterXhtml("What the River Kept", LOREM),
            )
        )
    }

    /** Valid zip, but no META-INF/container.xml — must fail cleanly, not crash. */
    fun malformedEpub() = cached("malformed.epub") { f ->
        zip(
            f,
            listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "OEBPS/random.xhtml" to "<html><body><p>orphan</p></body></html>".toByteArray(),
            )
        )
    }

    // ----------------------------------------------------------------- PDF

    private fun PDPageContentStream.line(
        text: String,
        size: Float,
        x: Float,
        y: Float,
        bold: Boolean = false,
    ) {
        beginText()
        setFont(if (bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA, size)
        newLineAtOffset(x, y)
        showText(text)
        endText()
    }

    private fun wrap(text: String, perLine: Int): List<String> =
        text.split(" ").filter { it.isNotBlank() }
            .fold(mutableListOf<String>()) { acc, w ->
                if (acc.isEmpty() || (acc.last().length + w.length + 1) > perLine) acc.add(w)
                else acc[acc.lastIndex] = acc.last() + " " + w
                acc
            }

    /**
     * Six pages of continuous prose.
     *
     * Each page opens with a distinct sentence rather than repeating the same body
     * text. Identical pages are not what books look like, and they make the header
     * detector behave pathologically: with the same first line on every page, it
     * correctly concludes that line is running furniture and removes the whole book.
     */
    fun singleColumnPdf() = cached("single-column.pdf") { f ->
        PDDocument().use { doc ->
            repeat(6) { p ->
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    var y = 720f
                    val body = "Section ${p + 1} begins here with its own opening words. $LOREM"
                    wrap(body, 70).forEach { l -> cs.line(l, 11f, 72f, y); y -= 16f }
                }
            }
            doc.save(f)
        }
    }

    /**
     * A PDF that knows what it is called, and opens with a title page.
     *
     * Both metadata sources at once so the resolver's order of preference can be
     * exercised, and the title page is set large so the page-one fallback has
     * something real to find when the info dictionary is junk.
     */
    fun titledPdf() = cached("titled.pdf") { f ->
        PDDocument().use { doc ->
            doc.documentInformation.title = "A History of Quiet Things"
            doc.documentInformation.author = "Ada Marlowe"

            val title = PDPage(PDRectangle.LETTER)
            doc.addPage(title)
            PDPageContentStream(doc, title).use { cs ->
                cs.line("A History of Quiet Things", 28f, 72f, 640f)
                cs.line("Ada Marlowe", 14f, 72f, 600f)
            }
            repeat(3) { p ->
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    var y = 720f
                    val body = "Section ${p + 1} begins here with its own opening words. $LOREM"
                    wrap(body, 70).forEach { l -> cs.line(l, 11f, 72f, y); y -= 16f }
                }
            }
            doc.save(f)
        }
    }

    /** The same book, but its metadata is a word processor's leftovers. */
    fun junkTitledPdf() = cached("junk-titled.pdf") { f ->
        PDDocument().use { doc ->
            doc.documentInformation.title = "Microsoft Word - quiet_things_FINAL_v3.doc"

            val title = PDPage(PDRectangle.LETTER)
            doc.addPage(title)
            PDPageContentStream(doc, title).use { cs ->
                cs.line("A History of Quiet Things", 28f, 72f, 640f)
            }
            repeat(2) { p ->
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    var y = 720f
                    wrap("Section ${p + 1}. $LOREM", 70).forEach { l -> cs.line(l, 11f, 72f, y); y -= 16f }
                }
            }
            doc.save(f)
        }
    }

    fun twoColumnPdf() = cached("two-column.pdf") { f ->
        PDDocument().use { doc ->
            repeat(4) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    // Balance the columns the way a real two-column layout does.
                    // An uneven split leaves one side looking like a ragged margin
                    // rather than a column, which is exactly what ColumnDetector
                    // is built to reject.
                    val lines = wrap(LOREM, 34)
                    val half = (lines.size + 1) / 2
                    var y = 720f
                    lines.take(half).forEach { l -> cs.line(l, 10f, 60f, y); y -= 15f }
                    y = 720f
                    lines.drop(half).forEach { l -> cs.line(l, 10f, 330f, y); y -= 15f }
                }
            }
            doc.save(f)
        }
    }

    /** Running header, footer page numbers, and deliberate end-of-line hyphenation. */
    fun headerFooterPdf() = cached("header-footer.pdf") { f ->
        PDDocument().use { doc ->
            for (p in 1..8) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.line("A HISTORY OF QUIET THINGS", 9f, 72f, 750f)
                    var y = 700f
                    listOf(
                        "Distributed sys-",
                        "tems are a col-",
                        "lection of independent computers that appear",
                        "to their users as a single coherent system.",
                    ).forEach { l -> cs.line(l, 11f, 72f, y); y -= 16f }
                    cs.line("$p", 9f, 300f, 40f)
                }
            }
            doc.save(f)
        }
    }

    /** Large centered bold chapter headings, for structure detection. */
    /**
     * The same three chapters, declared in the document's own outline.
     *
     * The pair matters: [chapteredPdf] *looks* chaptered and says nothing, while
     * this one says so. Only the declaration is trusted now, so the two fixtures
     * encode the policy between them.
     */
    fun outlinedPdf() = cached("outlined.pdf") { f ->
        PDDocument().use { doc ->
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline

            listOf(
                "Chapter 1" to "The Weight of Silence",
                "Chapter 2" to "What the River Kept",
                "Chapter 3" to "A Longer Winter",
            ).forEach { (label, title) ->
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.line(label, 10f, 250f, 700f)
                    cs.line(title, 22f, 180f, 660f, bold = true)
                    var y = 600f
                    wrap(LOREM, 70).forEach { l -> cs.line(l, 11f, 72f, y); y -= 16f }
                }
                val item = PDOutlineItem()
                item.title = title
                item.setDestination(page)
                outline.addLast(item)
            }
            doc.save(f)
        }
    }

    /** Looks chaptered to a human, declares nothing a machine can read. */
    fun chapteredPdf() = cached("chaptered.pdf") { f ->
        PDDocument().use { doc ->
            listOf(
                "Chapter 1" to "The Weight of Silence",
                "Chapter 2" to "What the River Kept",
                "Chapter 3" to "A Longer Winter",
            ).forEach { (label, title) ->
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.line(label, 10f, 250f, 700f)
                    cs.line(title, 22f, 180f, 660f, bold = true)
                    var y = 600f
                    wrap(LOREM, 70).forEach { l -> cs.line(l, 11f, 72f, y); y -= 16f }
                }
            }
            doc.save(f)
        }
    }

    /** Renders singleColumnPdf to images and rebuilds from them: no text layer at all. */
    fun imageOnlyPdf() = cached("scanned.pdf") { f ->
        PDDocument.load(singleColumnPdf()).use { src ->
            val renderer = PDFRenderer(src)
            PDDocument().use { out ->
                for (i in 0 until src.numberOfPages) {
                    val img: BufferedImage = renderer.renderImageWithDPI(i, 120f)
                    val page = PDPage(PDRectangle(img.width.toFloat(), img.height.toFloat()))
                    out.addPage(page)
                    val xobj = LosslessFactory.createFromImage(out, img)
                    PDPageContentStream(out, page).use { cs ->
                        cs.drawImage(xobj, 0f, 0f, img.width.toFloat(), img.height.toFloat())
                    }
                }
                out.save(f)
            }
        }
    }

    fun largeBook() = cached("large.pdf") { f ->
        PDDocument().use { doc ->
            val body = wrap(LOREM, 70)
            repeat(420) { p ->
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    if (p % 40 == 0) cs.line("Chapter ${p / 40 + 1}", 20f, 200f, 700f, bold = true)
                    var y = 650f
                    body.forEach { l -> cs.line(l, 11f, 72f, y); y -= 16f }
                }
            }
            doc.save(f)
        }
    }

    /**
     * Structurally unparseable: correct magic bytes so format detection routes it to
     * the PDF path, then noise. PDFBox must fail to open this.
     */
    fun corruptPdf() = cached("corrupt.pdf") { f ->
        val rnd = java.util.Random(42)
        val noise = ByteArray(2048).also { rnd.nextBytes(it) }
        f.writeBytes("%PDF-1.7\n".toByteArray() + noise)
    }

    /**
     * Truncated mid-file, losing the trailing xref table.
     *
     * PDFBox's lenient parser *recovers* this by scanning for objects, so it opens
     * successfully and reports a plausible page count with partial content. That is
     * the dangerous case: silently importing a fraction of a book as if it were whole.
     * The pipeline is responsible for noticing, not the text source.
     */
    fun truncatedPdf() = cached("truncated.pdf") { f ->
        val good = singleColumnPdf().readBytes()
        f.writeBytes(good.copyOfRange(0, good.size / 3))
    }

    /** A PNG with an .epub extension: format detection must catch this by magic bytes. */
    fun unsupportedFile() = cached("actually-a-png.epub") { f ->
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run { color = Color.GRAY; fillRect(0, 0, 8, 8); dispose() }
        }
        javax.imageio.ImageIO.write(img, "png", f)
    }
}
