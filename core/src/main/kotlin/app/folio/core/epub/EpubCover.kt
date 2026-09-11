package app.folio.core.epub

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Finds the cover image a book declares.
 *
 * There is no one way to declare one. EPUB 3 marks a manifest item with
 * `properties="cover-image"`. EPUB 2 has no such property and instead points at a
 * manifest id from `<meta name="cover">`. Retailers asked publishers to do both, so
 * books carry either, both, or neither, and the rules have to be tried in order
 * rather than chosen between.
 *
 * Returned href is relative to the OPF, exactly as the manifest writes it; resolving
 * it against the package directory is the container's job.
 *
 * Every rule insists the item is actually an image. The commonest false positive in
 * real books is an item whose id is literally "cover" and whose href is `cover.xhtml`
 * — the page that displays the cover, not the cover. Storing that would write markup
 * to disk as a picture.
 *
 * Returns null freely. A book with no cover gets no cover: the design already falls
 * back to a gradient swatch, and a wrong cover is worse than an honest absence.
 */
object EpubCover {

    fun hrefIn(opf: Document): String? =
        byProperty(opf) ?: byMetaReference(opf) ?: byName(opf)

    /** EPUB 3: `<item properties="cover-image" …>`. */
    private fun byProperty(opf: Document): String? =
        opf.select("manifest > item[properties~=(^|\\s)cover-image(\\s|$)]")
            .firstOrNull { it.isImage() }
            ?.href()

    /** EPUB 2: `<meta name="cover" content="{manifest id}"/>`. */
    private fun byMetaReference(opf: Document): String? {
        val id = opf.selectFirst("metadata > meta[name=cover]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return opf.select("manifest > item")
            .firstOrNull { it.attr("id") == id && it.isImage() }
            ?.href()
    }

    /**
     * Neither declaration present: take an image that calls itself a cover.
     *
     * Id first, then href, because an id is chosen deliberately while a filename may
     * just happen to contain the word.
     */
    private fun byName(opf: Document): String? {
        val images = opf.select("manifest > item").filter { it.isImage() }
        return images.firstOrNull { it.attr("id").contains("cover", ignoreCase = true) }?.href()
            ?: images.firstOrNull {
                it.attr("href").substringAfterLast('/').contains("cover", ignoreCase = true)
            }?.href()
    }

    private fun Element.isImage(): Boolean =
        attr("media-type").startsWith("image/", ignoreCase = true)

    private fun Element.href(): String? = attr("href").trim().takeIf { it.isNotEmpty() }
}
