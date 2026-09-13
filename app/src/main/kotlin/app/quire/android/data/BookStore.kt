package app.quire.android.data

import app.quire.core.model.Chapter
import app.quire.core.model.ChapterRef
import app.quire.core.model.toRef
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

    /**
     * A book's directory as a path, with no side effect.
     *
     * Deliberately does not create anything. An earlier version called `mkdirs()`
     * here, which meant merely *reading* a missing chapter recreated the book's
     * directory — so a deleted book left an empty folder behind every time anything
     * looked for it. Creation belongs to the write paths only.
     */
    fun bookDir(id: String): File = File(root, "books/$id")

    private fun ensureBookDir(id: String): File = bookDir(id).apply { mkdirs() }

    private fun chapterFile(id: String, index: Int) =
        File(bookDir(id), "chapters/%03d.json".format(index))

    /** Streams rather than buffering: an imported book can be hundreds of megabytes. */
    fun writeOriginal(id: String, source: InputStream, extension: String): File {
        val target = File(ensureBookDir(id), "original.$extension")
        target.outputStream().buffered().use { out -> source.copyTo(out, DEFAULT_BUFFER_SIZE) }
        return target
    }

    fun originalOf(id: String): File? =
        bookDir(id).listFiles()?.firstOrNull { it.name.startsWith("original.") }

    fun writeChapters(id: String, chapters: List<Chapter>) {
        File(ensureBookDir(id), "chapters").mkdirs()
        chapters.forEach { chapter ->
            chapterFile(id, chapter.index).writeText(json.encodeToString(chapter))
        }
        // An index alongside the chapters, so the table of contents costs one read
        // rather than one per chapter. On a four-hundred-chapter book the
        // difference is the sheet opening instantly or visibly stalling.
        indexFile(id).writeText(json.encodeToString(chapters.map { it.toRef() }))
    }

    private fun indexFile(id: String) = File(bookDir(id), "chapters/index.json")

    /** Chapter titles and offsets without their content. */
    fun readChapterIndex(id: String): List<ChapterRef> {
        val f = indexFile(id)
        if (!f.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<ChapterRef>>(f.readText()) }
            .getOrDefault(emptyList())
    }

    /** Returns null for a missing or unreadable chapter rather than throwing. */
    fun readChapter(id: String, index: Int): Chapter? {
        val f = chapterFile(id, index)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<Chapter>(f.readText()) }.getOrNull()
    }

    fun chapterCount(id: String): Int =
        File(bookDir(id), "chapters").listFiles()
            ?.count { it.extension == "json" && it.name != "index.json" } ?: 0

    fun writeCover(id: String, bytes: ByteArray): String {
        val f = File(ensureBookDir(id), "cover.jpg")
        f.writeBytes(bytes)
        return f.absolutePath
    }

    /** Removes every trace of a book. Safe to call on a book that was never written. */
    fun delete(id: String) {
        bookDir(id).deleteRecursively()
    }

    /** Ids of every book directory currently on disk, imported or not. */
    fun allBookIds(): List<String> =
        File(root, "books").listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()
            ?: emptyList()

    fun freeBytes(): Long = root.usableSpace

    /** True when [needed] bytes plus a working margin will fit. */
    fun hasRoomFor(needed: Long): Boolean = freeBytes() > needed * 2 + MARGIN_BYTES

    private companion object {
        /** Headroom for the normalized output alongside the copied original. */
        const val MARGIN_BYTES = 32L * 1024 * 1024
    }
}
