package app.quire.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val index: Int,
    val title: String?,
    val blocks: List<ContentBlock>,
    val startCharOffset: Int,
    val charCount: Int,
) {
    /**
     * Each block's text, built once for the life of the chapter.
     *
     * [plainText] is a computed property: every read rebuilds the string from its
     * spans. The Reader reads it per visible block per recomposition, so a page turn
     * rebuilt the full text of everything on screen — on a single 400k-character
     * block, a 400k-character allocation per frame. Pagination reads it too, once
     * per block per repagination.
     *
     * Outside the constructor and lazy, so it is derived rather than stored: it
     * takes no part in equality, and kotlinx.serialization writes only constructor
     * properties, so nothing extra reaches the JSON on disk.
     */
    val blockTexts: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        blocks.map { it.plainText }
    }
}

/** A chapter's identity without its content, so the library can list without loading. */
@Serializable
data class ChapterRef(
    val index: Int,
    val title: String?,
    val startCharOffset: Int,
    val charCount: Int,
)

fun Chapter.toRef() = ChapterRef(index, title, startCharOffset, charCount)
