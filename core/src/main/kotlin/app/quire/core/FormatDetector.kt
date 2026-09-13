package app.quire.core

import app.quire.core.model.SourceFormat
import java.io.File

/**
 * Identifies a file's real format from its leading bytes.
 *
 * Extensions lie — a PNG saved as `.epub`, a `.txt` that is really a PDF — and
 * trusting one means failing deep inside a parser with a confusing error instead of
 * cleanly at the door. Detection is by signature, and the extension is only ever a
 * tie-breaker for formats that have no signature at all.
 */
object FormatDetector {

    private val PDF = byteArrayOf(0x25, 0x50, 0x44, 0x46)          // %PDF
    private val ZIP = byteArrayOf(0x50, 0x4B)                      // PK
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val GIF = "GIF8".toByteArray()

    fun detect(file: File): SourceFormat? {
        val head = runCatching {
            file.inputStream().use { s -> ByteArray(64).let { it.copyOf(s.read(it).coerceAtLeast(0)) } }
        }.getOrElse { return null }

        if (head.isEmpty()) return null

        return when {
            head.startsWith(PDF) -> SourceFormat.PDF_TEXT
            head.startsWith(PNG) || head.startsWith(JPEG) || head.startsWith(GIF) -> null
            head.startsWith(ZIP) -> if (looksLikeEpub(file)) SourceFormat.EPUB else null
            isProbablyText(head) -> SourceFormat.TXT
            else -> null
        }
    }

    /** A zip is only an EPUB if it declares the media type or carries a container. */
    private fun looksLikeEpub(file: File): Boolean = runCatching {
        java.util.zip.ZipFile(file).use { zip ->
            zip.getEntry("META-INF/container.xml") != null ||
                zip.getEntry("mimetype")?.let { entry ->
                    zip.getInputStream(entry).use { it.readBytes() }
                        .toString(Charsets.UTF_8).trim() == "application/epub+zip"
                } == true
        }
    }.getOrDefault(false)

    /**
     * Text has no signature, so it is inferred: decodable as UTF-8 and free of the
     * NUL and control bytes that mark a binary file.
     */
    private fun isProbablyText(head: ByteArray): Boolean {
        if (head.any { it == 0.toByte() }) return false
        val suspicious = head.count { b ->
            val v = b.toInt() and 0xFF
            v < 0x09 || (v in 0x0E..0x1F) || v == 0x7F
        }
        return suspicious == 0
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
}
