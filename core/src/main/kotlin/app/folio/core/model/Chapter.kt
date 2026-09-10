package app.folio.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val index: Int,
    val title: String?,
    val blocks: List<ContentBlock>,
    val startCharOffset: Int,
    val charCount: Int,
)

/** A chapter's identity without its content, so the library can list without loading. */
@Serializable
data class ChapterRef(
    val index: Int,
    val title: String?,
    val startCharOffset: Int,
    val charCount: Int,
)

fun Chapter.toRef() = ChapterRef(index, title, startCharOffset, charCount)
