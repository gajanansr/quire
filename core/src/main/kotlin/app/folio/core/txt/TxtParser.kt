package app.folio.core.txt

import app.folio.core.model.*
import java.io.File

/**
 * Plain text. Paragraphs are blank-line separated; a short line matching a chapter
 * pattern starts a chapter. Anything that does not match stays where it is — the
 * parser never invents a boundary it is not confident about (spec section 7).
 */
class TxtParser {

    private val headingPattern = Regex(
        """^(chapter|part|book|section)\s+([0-9]+|[ivxlcdm]+)\b.*$|^([0-9]{1,3})\.?$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(file: File, id: String): Book {
        val raw = runCatching { file.readText() }
            .getOrElse { return failed(id, file, FailureReason.EXTRACTION_FAILED) }
        if (raw.isBlank()) return failed(id, file, FailureReason.EMPTY_DOCUMENT)

        val paragraphs = raw.split(Regex("\\n\\s*\\n"))
            .map { it.replace(Regex("\\s*\\n\\s*"), " ").trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return failed(id, file, FailureReason.EMPTY_DOCUMENT)

        return Book(
            id = id,
            title = paragraphs.first().take(120),
            author = null,
            coverPath = null,
            metadata = BookMetadata(),
            sourceFormat = SourceFormat.TXT,
            status = ProcessingStatus.Ready,
            chapters = groupIntoChapters(paragraphs),
        )
    }

    private fun isHeading(p: String) = p.length <= 60 && headingPattern.matches(p.trim())

    private fun groupIntoChapters(paragraphs: List<String>): List<Chapter> {
        class Group(val title: String?, val blocks: MutableList<ContentBlock> = mutableListOf())

        val groups = mutableListOf<Group>()
        paragraphs.forEach { p ->
            val heading = isHeading(p)
            if (heading || groups.isEmpty()) groups += Group(if (heading) p.trim() else null)
            if (heading) {
                groups.last().blocks += ContentBlock.Heading(1, listOf(InlineSpan(p.trim())))
            } else {
                groups.last().blocks += ContentBlock.Paragraph(listOf(InlineSpan(p)))
            }
        }

        var offset = 0
        return groups.mapIndexed { i, g ->
            val count = g.blocks.sumOf { it.plainText.length }
            Chapter(i, g.title, g.blocks, offset, count).also { offset += count }
        }
    }

    private fun failed(id: String, file: File, reason: FailureReason) = Book(
        id = id, title = file.nameWithoutExtension, author = null, coverPath = null,
        metadata = BookMetadata(), sourceFormat = SourceFormat.TXT,
        status = ProcessingStatus.Failed(reason), chapters = emptyList(),
    )
}
