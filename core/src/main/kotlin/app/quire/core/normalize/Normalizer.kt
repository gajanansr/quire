package app.quire.core.normalize

import app.quire.core.model.Book
import app.quire.core.model.BookMetadata
import app.quire.core.model.Chapter
import app.quire.core.model.ProcessingStatus
import app.quire.core.model.SourceFormat
import app.quire.core.model.plainText

/**
 * Assembles the final [Book] and guarantees the invariants the reader depends on.
 *
 * Chapter offsets are recomputed here rather than trusted, because every producer
 * upstream builds them independently and a drift of one character silently breaks
 * resume positions and progress for the whole book.
 */
class Normalizer {

    fun assemble(
        id: String,
        title: String,
        author: String?,
        metadata: BookMetadata,
        sourceFormat: SourceFormat,
        chapters: List<Chapter>,
        coverPath: String? = null,
        reflowFailed: Boolean = false,
    ): Book = Book(
        id = id,
        title = title.trim().ifEmpty { "Untitled" },
        author = author?.trim()?.takeIf { it.isNotEmpty() },
        coverPath = coverPath,
        metadata = metadata,
        sourceFormat = sourceFormat,
        status = ProcessingStatus.Ready,
        chapters = withOffsets(chapters),
        reflowFailed = reflowFailed,
    )

    /** Drops empty chapters, then renumbers and re-offsets what remains. */
    fun withOffsets(chapters: List<Chapter>): List<Chapter> {
        var offset = 0
        return chapters
            .filter { it.blocks.isNotEmpty() }
            .mapIndexed { i, c ->
                val count = c.blocks.sumOf { it.plainText.length }
                Chapter(i, c.title, c.blocks, offset, count).also { offset += count }
            }
    }
}
