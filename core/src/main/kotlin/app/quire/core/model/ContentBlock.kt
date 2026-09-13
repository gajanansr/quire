package app.quire.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class InlineStyle { EMPHASIS, STRONG, CODE }

@Serializable
data class InlineSpan(val text: String, val style: Set<InlineStyle> = emptySet())

/**
 * The reader renders these and nothing else. It has no knowledge of whether the
 * book arrived as EPUB, PDF, OCR output, or plain text.
 */
@Serializable
sealed interface ContentBlock {
    @Serializable data class Paragraph(val spans: List<InlineSpan>) : ContentBlock
    @Serializable data class Heading(val level: Int, val spans: List<InlineSpan>) : ContentBlock
    @Serializable data class ListItem(val ordinal: Int?, val spans: List<InlineSpan>) : ContentBlock
    @Serializable data class BlockQuote(val spans: List<InlineSpan>) : ContentBlock
    @Serializable data class Image(val path: String, val caption: String? = null) : ContentBlock
    @Serializable data class PageBreak(val sourcePage: Int) : ContentBlock
}

val ContentBlock.plainText: String
    get() = when (this) {
        is ContentBlock.Paragraph -> spans.joinToString("") { it.text }
        is ContentBlock.Heading -> spans.joinToString("") { it.text }
        is ContentBlock.ListItem -> spans.joinToString("") { it.text }
        is ContentBlock.BlockQuote -> spans.joinToString("") { it.text }
        is ContentBlock.Image -> caption.orEmpty()
        is ContentBlock.PageBreak -> ""
    }
