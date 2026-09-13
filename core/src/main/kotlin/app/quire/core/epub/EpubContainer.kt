package app.quire.core.epub

import app.quire.core.model.BookMetadata
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads the EPUB container: the zip, `META-INF/container.xml`, the OPF package
 * document, and the navigation document.
 *
 * Every accessor is tolerant of a broken book. A missing or unparseable part
 * yields null or an empty collection rather than an exception, because a corrupt
 * EPUB must surface as the designed error state, never as a crash (spec section 12).
 * The one exception is the constructor: a file that is not a zip at all cannot be
 * opened, and that failure is the caller's to catch.
 */
class EpubContainer(file: File) : Closeable {

    private val zip = ZipFile(file)

    /** Full path of the OPF package document, or null if the container is unreadable. */
    private val opf: String? by lazy {
        val xml = readEntry("META-INF/container.xml")?.toString(Charsets.UTF_8) ?: return@lazy null
        parseXml(xml)?.selectFirst("rootfile[full-path]")?.attr("full-path")?.takeIf { it.isNotBlank() }
    }

    /** Directory the OPF lives in; hrefs inside the OPF resolve against it. */
    private val baseDir: String by lazy {
        opf?.substringBeforeLast('/', "")?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
    }

    private val opfDoc: Document? by lazy {
        val path = opf ?: return@lazy null
        readEntry(path)?.toString(Charsets.UTF_8)?.let { parseXml(it) }
    }

    fun opfPath(): String? = opf

    fun readEntry(path: String): ByteArray? {
        val entry = zip.getEntry(path) ?: return null
        return runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
    }

    fun titleAndAuthor(): Pair<String, String?> {
        val doc = opfDoc ?: return "Untitled" to null
        val title = doc.selectFirst("metadata > title, metadata > dc|title")?.text()
            ?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled"
        val author = doc.selectFirst("metadata > creator, metadata > dc|creator")?.text()
            ?.trim()?.takeIf { it.isNotEmpty() }
        return title to author
    }

    fun metadata(): BookMetadata {
        val doc = opfDoc ?: return BookMetadata()
        fun one(vararg selectors: String): String? = selectors.firstNotNullOfOrNull {
            doc.selectFirst(it)?.text()?.trim()?.takeIf { t -> t.isNotEmpty() }
        }
        return BookMetadata(
            language = one("metadata > language", "metadata > dc|language"),
            publisher = one("metadata > publisher", "metadata > dc|publisher"),
            identifier = one("metadata > identifier", "metadata > dc|identifier"),
            subjects = doc.select("metadata > subject, metadata > dc|subject")
                .map { it.text().trim() }.filter { it.isNotEmpty() },
            description = one("metadata > description", "metadata > dc|description"),
        )
    }

    /**
     * The declared cover image, or null when the book has none.
     *
     * Resolution rules live in [EpubCover]; reading the bytes lives here because it
     * needs the zip. A book with no cover returns null rather than a guess: the
     * Library falls back to a gradient swatch, and a wrong cover is worse.
     */
    fun coverImage(): ByteArray? {
        val doc = opfDoc ?: return null
        val href = EpubCover.hrefIn(doc) ?: return null
        return readEntry(resolve(href))
    }

    /** Spine documents in reading order, as full zip paths. */
    fun spineHrefs(): List<String> {
        val doc = opfDoc ?: return emptyList()
        val manifest = doc.select("manifest > item").associate {
            it.attr("id") to it.attr("href")
        }
        return doc.select("spine > itemref").mapNotNull { ref ->
            manifest[ref.attr("idref")]?.let { resolve(it) }
        }
    }

    /** Maps a spine document's full path to its title from the EPUB 3 nav or EPUB 2 NCX. */
    fun navTitles(): Map<String, String> {
        val doc = opfDoc ?: return emptyMap()
        navFromEpub3(doc)?.takeIf { it.isNotEmpty() }?.let { return it }
        return navFromNcx(doc)
    }

    private fun navFromEpub3(doc: Document): Map<String, String>? {
        val navHref = doc.selectFirst("manifest > item[properties~=(^|\\s)nav(\\s|$)]")
            ?.attr("href") ?: return null
        val xml = readEntry(resolve(navHref))?.toString(Charsets.UTF_8) ?: return null
        val nav = parseXml(xml) ?: return null
        return nav.select("nav a[href]").mapNotNull { a ->
            val href = a.attr("href").substringBefore('#').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val text = a.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            resolve(href) to text
        }.toMap()
    }

    private fun navFromNcx(doc: Document): Map<String, String> {
        val ncxHref = doc.selectFirst("manifest > item[media-type=application/x-dtbncx+xml]")
            ?.attr("href") ?: return emptyMap()
        val xml = readEntry(resolve(ncxHref))?.toString(Charsets.UTF_8) ?: return emptyMap()
        val ncx = parseXml(xml) ?: return emptyMap()
        return ncx.select("navPoint").mapNotNull { point ->
            val href = point.selectFirst("content[src]")?.attr("src")
                ?.substringBefore('#')?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val text = point.selectFirst("navLabel > text")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            resolve(href) to text
        }.toMap()
    }

    /** Cover image bytes, via the EPUB 3 `cover-image` property or the EPUB 2 meta pointer. */
    fun coverBytes(): ByteArray? {
        val doc = opfDoc ?: return null
        val byProperty = doc.selectFirst("manifest > item[properties~=(^|\\s)cover-image(\\s|$)]")
            ?.attr("href")
        val byMeta = doc.selectFirst("metadata > meta[name=cover]")?.attr("content")
            ?.let { id -> doc.selectFirst("manifest > item[id=$id]")?.attr("href") }
        val href = byProperty ?: byMeta ?: return null
        return readEntry(resolve(href))
    }

    /** Resolves an OPF-relative href to a full zip path, collapsing `..` segments. */
    private fun resolve(href: String): String {
        val clean = href.substringBefore('#')
        if (clean.startsWith("/")) return clean.removePrefix("/")
        val parts = mutableListOf<String>()
        (baseDir + clean).split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun parseXml(xml: String): Document? =
        runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull()

    override fun close() = zip.close()
}
