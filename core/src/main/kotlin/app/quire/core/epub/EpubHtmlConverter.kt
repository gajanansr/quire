package app.quire.core.epub

import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.model.InlineStyle
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Converts a spine document's XHTML into normalized blocks.
 *
 * The reader renders the normalized model, not arbitrary EPUB markup, so this is
 * where structure is preserved and presentation is discarded. Meaningful structure
 * — headings, paragraphs, lists, quotes, emphasis, images — survives; scripts,
 * styles, and navigation furniture do not.
 *
 * An element this does not recognise degrades to a paragraph rather than being
 * dropped: unknown markup is not a reason to lose the author's text.
 */
class EpubHtmlConverter {

    private companion object {
        val DROPPED = setOf("script", "style", "nav", "head", "title", "link", "meta")
        val HEADINGS = mapOf("h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6)
        val EMPHASIS_TAGS = setOf("em", "i", "cite", "dfn", "var")
        val STRONG_TAGS = setOf("strong", "b")
        val CODE_TAGS = setOf("code", "kbd", "samp", "tt")

        /** Elements that contain other blocks rather than text of their own. */
        val CONTAINERS = setOf(
            "html", "body", "div", "section", "article", "main", "aside",
            "header", "footer", "figure", "table", "tbody", "thead", "tr",
        )
    }

    fun convert(xhtml: String): List<ContentBlock> {
        val doc = runCatching { Jsoup.parse(xhtml) }.getOrNull() ?: return emptyList()
        val body = doc.body() ?: return emptyList()
        val out = mutableListOf<ContentBlock>()
        body.childNodes().forEach { walk(it, out) }
        return out
    }

    private fun walk(node: Node, out: MutableList<ContentBlock>) {
        when (node) {
            is TextNode -> {
                // Loose text directly under a container still belongs to the book.
                val text = collapse(node.text())
                if (text.isNotEmpty()) out += ContentBlock.Paragraph(listOf(InlineSpan(text)))
            }

            is Element -> {
                val tag = node.tagName().lowercase()
                when {
                    tag in DROPPED -> Unit

                    tag == "img" -> {
                        val src = node.attr("src").takeIf { it.isNotBlank() } ?: return
                        out += ContentBlock.Image(src, node.attr("alt").takeIf { it.isNotBlank() })
                    }

                    tag in HEADINGS -> emit(out) {
                        ContentBlock.Heading(HEADINGS.getValue(tag), it)
                    }.let { block -> block(node) }

                    tag == "p" -> emit(out) { ContentBlock.Paragraph(it) }.let { it(node) }

                    tag == "blockquote" -> emit(out) { ContentBlock.BlockQuote(it) }.let { it(node) }

                    tag == "ul" || tag == "ol" -> {
                        val ordered = tag == "ol"
                        node.children().filter { it.tagName().equals("li", true) }
                            .forEachIndexed { i, li ->
                                val spans = inline(li)
                                if (spans.any { it.text.isNotBlank() }) {
                                    out += ContentBlock.ListItem(if (ordered) i + 1 else null, spans)
                                }
                            }
                    }

                    tag in CONTAINERS -> node.childNodes().forEach { walk(it, out) }

                    // Unknown element: keep its text rather than losing it.
                    else -> {
                        if (node.children().any { it.tagName().lowercase() in CONTAINERS ||
                                it.tagName().lowercase() in HEADINGS || it.tagName().lowercase() == "p" }
                        ) {
                            node.childNodes().forEach { walk(it, out) }
                        } else {
                            emit(out) { ContentBlock.Paragraph(it) }.let { it(node) }
                        }
                    }
                }
            }
        }
    }

    /** Builds a block from an element's inline content, skipping it when empty. */
    private fun emit(
        out: MutableList<ContentBlock>,
        make: (List<InlineSpan>) -> ContentBlock,
    ): (Element) -> Unit = { element ->
        val spans = inline(element)
        if (spans.any { it.text.isNotBlank() }) out += make(spans)
        // An <img> nested inside a paragraph is a block in its own right.
        element.select("img[src]").forEach {
            out += ContentBlock.Image(it.attr("src"), it.attr("alt").takeIf { a -> a.isNotBlank() })
        }
    }

    /** Flattens an element's descendants into styled spans, merging adjacent equals. */
    private fun inline(element: Element): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        collect(element, emptySet(), spans)
        return merge(spans)
    }

    private fun collect(node: Node, styles: Set<InlineStyle>, out: MutableList<InlineSpan>) {
        when (node) {
            is TextNode -> {
                val t = node.text()
                if (t.isNotEmpty()) out += InlineSpan(t, styles)
            }

            is Element -> {
                val tag = node.tagName().lowercase()
                if (tag in DROPPED || tag == "img") return
                val next = when (tag) {
                    in EMPHASIS_TAGS -> styles + InlineStyle.EMPHASIS
                    in STRONG_TAGS -> styles + InlineStyle.STRONG
                    in CODE_TAGS -> styles + InlineStyle.CODE
                    else -> styles
                }
                node.childNodes().forEach { collect(it, next, out) }
            }
        }
    }

    /**
     * Collapses whitespace across span boundaries, then merges neighbours that share
     * a style so the reader is not handed a span per character.
     */
    private fun merge(spans: List<InlineSpan>): List<InlineSpan> {
        val collapsed = mutableListOf<InlineSpan>()
        var atStart = true
        spans.forEach { span ->
            var text = span.text.replace(Regex("\\s+"), " ")
            if (atStart) text = text.trimStart()
            if (text.isEmpty()) return@forEach
            atStart = false
            collapsed += span.copy(text = text)
        }
        if (collapsed.isNotEmpty()) {
            val last = collapsed.last()
            collapsed[collapsed.lastIndex] = last.copy(text = last.text.trimEnd())
        }

        val merged = mutableListOf<InlineSpan>()
        collapsed.filter { it.text.isNotEmpty() }.forEach { span ->
            val prev = merged.lastOrNull()
            if (prev != null && prev.style == span.style) {
                merged[merged.lastIndex] = prev.copy(text = prev.text + span.text)
            } else {
                merged += span
            }
        }
        return merged
    }

    private fun collapse(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
