package app.folio.core.model

import kotlinx.serialization.Serializable

/**
 * Canonical location in a book. Deliberately not a page number: reflowed text
 * repaginates whenever typography changes, so a page number is not a stable
 * identity for a location. Pages are derived by the paginator and never persisted.
 */
@Serializable
data class ReadingPosition(
    val chapterIndex: Int,
    val blockIndex: Int,
    val charOffset: Int,
) {
    companion object { val START = ReadingPosition(0, 0, 0) }
}

fun Book.progressAt(position: ReadingPosition): Double {
    val total = totalChars
    if (total <= 0) return 0.0
    val chapter = chapters.getOrNull(position.chapterIndex)
        ?: return if (position.chapterIndex >= chapters.size) 1.0 else 0.0
    val within = position.charOffset.coerceIn(0, chapter.charCount)
    return ((chapter.startCharOffset + within).toDouble() / total).coerceIn(0.0, 1.0)
}
