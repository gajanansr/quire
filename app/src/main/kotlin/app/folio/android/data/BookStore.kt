package app.folio.android.data

import app.folio.core.model.Chapter
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/**
 * The on-disk half of a book: the imported original, the cover, and the chapters.
 *
 * Chapters are stored one file per chapter rather than as a single document, so the
 * reader can open chapter 40 of a long book without deserializing the other 39, and
 * an interrupted import keeps whatever it already finished.
 *
 * Everything for a book lives under one directory, which makes deletion a single
 * recursive remove and makes a failed import trivially cleanable — a half-imported
 * book must never survive to appear in the Library.
 */
class BookStore(private val root: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun bookDir(id: String): File = File(root, "books/$id").apply { mkdirs() }

    private fun chapterFile(id: String, index: Int) =
        File(bookDir(id), "chapters/%03d.json".format(index))

    /** Streams rather than buffering: an imported book can be hundreds of megabytes. */
    fun writeOriginal(id: String, source: InputStream, extension: String): File {
        val target = File(bookDir(id), "original.$extension")
        target.outputStream().buffered().use { out -> source.copyTo(out, DEFAULT_BUFFER_SIZE) }
        return target
    }

    fun originalOf(id: String): File? =
        bookDir(id).listFiles()?.firstOrNull { it.name.startsWith("original.") }

    fun writeChapters(id: String, chapters: List<Chapter>) {
        File(bookDir(id), "chapters").mkdirs()
        chapters.forEach { chapter ->
            chapterFile(id, chapter.index).writeText(json.encodeToString(chapter))
        }
    }

    /** Returns null for a missing or unreadable chapter rather than throwing. */
    fun readChapter(id: String, index: Int): Chapter? {
        val f = chapterFile(id, index)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<Chapter>(f.readText()) }.getOrNull()
    }

    fun chapterCount(id: String): Int =
        File(bookDir(id), "chapters").listFiles()?.count { it.extension == "json" } ?: 0

    fun writeCover(id: String, bytes: ByteArray): String {
        val f = File(bookDir(id), "cover.jpg")
        f.writeBytes(bytes)
        return f.absolutePath
    }

    /** Removes every trace of a book. Safe to call on a book that was never written. */
    fun delete(id: String) {
        File(root, "books/$id").deleteRecursively()
    }

    fun freeBytes(): Long = root.usableSpace

    /** True when [needed] bytes plus a working margin will fit. */
    fun hasRoomFor(needed: Long): Boolean = freeBytes() > needed * 2 + MARGIN_BYTES

    private companion object {
        /** Headroom for the normalized output alongside the copied original. */
        const val MARGIN_BYTES = 32L * 1024 * 1024
    }
}
