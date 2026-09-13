package app.quire.core.epub

import app.quire.core.model.*
import java.io.File

/**
 * Turns an EPUB into a normalized [Book]. One chapter per spine document, which is
 * the boundary the author actually declared — EPUB never needs the heuristic chapter
 * detection that PDFs do.
 *
 * Titles prefer the navigation document, falling back to the chapter's own first
 * heading, and finally to null. A chapter with no discoverable title is normal and
 * is left untitled rather than given an invented one.
 */
class EpubParser(
    private val converter: EpubHtmlConverter = EpubHtmlConverter(),
) {

    fun parse(file: File, id: String): Book {
        val container = runCatching { EpubContainer(file) }.getOrNull()
            ?: return failed(file, id, FailureReason.UNSUPPORTED_FORMAT)

        return container.use { c ->
            if (c.opfPath() == null) return@use failed(file, id, FailureReason.CORRUPT_FILE)

            val spine = c.spineHrefs()
            if (spine.isEmpty()) return@use failed(file, id, FailureReason.CORRUPT_FILE)

            val navTitles = c.navTitles()
            var offset = 0
            val chapters = spine.mapIndexedNotNull { index, href ->
                val xhtml = c.readEntry(href)?.toString(Charsets.UTF_8) ?: return@mapIndexedNotNull null
                val blocks = converter.convert(xhtml)
                if (blocks.isEmpty()) return@mapIndexedNotNull null

                val title = navTitles[href] ?: firstHeading(blocks)
                val count = blocks.sumOf { it.plainText.length }
                Chapter(index, title, blocks, offset, count).also { offset += count }
            }.reindexed()

            if (chapters.isEmpty()) return@use failed(file, id, FailureReason.EMPTY_DOCUMENT)

            val (title, author) = c.titleAndAuthor()
            Book(
                id = id,
                title = title,
                author = author,
                coverPath = null, // written to disk by the storage layer in Plan 2
                metadata = c.metadata(),
                sourceFormat = SourceFormat.EPUB,
                status = ProcessingStatus.Ready,
                chapters = chapters,
            )
        }
    }

    private fun firstHeading(blocks: List<ContentBlock>): String? =
        blocks.filterIsInstance<ContentBlock.Heading>()
            .firstOrNull()?.plainText?.trim()?.takeIf { it.isNotEmpty() }

    /** Skipped spine entries would otherwise leave gaps in the chapter indices. */
    private fun List<Chapter>.reindexed(): List<Chapter> {
        var offset = 0
        return mapIndexed { i, c ->
            c.copy(index = i, startCharOffset = offset).also { offset += c.charCount }
        }
    }

    private fun failed(file: File, id: String, reason: FailureReason) = Book(
        id = id, title = file.nameWithoutExtension, author = null, coverPath = null,
        metadata = BookMetadata(), sourceFormat = SourceFormat.EPUB,
        status = ProcessingStatus.Failed(reason), chapters = emptyList(),
    )
}
