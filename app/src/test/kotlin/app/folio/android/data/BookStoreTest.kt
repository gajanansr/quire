package app.folio.android.data

import app.folio.core.model.Chapter
import app.folio.core.model.ContentBlock
import app.folio.core.model.InlineSpan
import app.folio.core.model.InlineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private fun store() = BookStore(temp.root)

    private fun chapter(index: Int, text: String) = Chapter(
        index = index,
        title = "Chapter $index",
        blocks = listOf(ContentBlock.Paragraph(listOf(InlineSpan(text)))),
        startCharOffset = index * 100,
        charCount = text.length,
    )

    @Test
    fun `original round trips byte for byte`() {
        val s = store()
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        val written = s.writeOriginal("b1", bytes.inputStream(), "epub")
        assertTrue(written.exists())
        assertTrue(bytes.contentEquals(written.readBytes()))
    }

    @Test
    fun `originalOf finds the copied file whatever its extension`() {
        val s = store()
        s.writeOriginal("b1", "hello".byteInputStream(), "pdf")
        assertEquals("original.pdf", s.originalOf("b1")?.name)
    }

    @Test
    fun `chapters are written one file each`() {
        val s = store()
        s.writeChapters("b1", (0..4).map { chapter(it, "text $it") })
        val files = File(s.bookDir("b1"), "chapters").listFiles()!!
        assertEquals(5, files.size)
        assertEquals(5, s.chapterCount("b1"))
    }

    @Test
    fun `a chapter reads back with its structure intact`() {
        val s = store()
        val original = Chapter(
            index = 2, title = "Titled",
            blocks = listOf(
                ContentBlock.Heading(1, listOf(InlineSpan("A Heading"))),
                ContentBlock.Paragraph(listOf(
                    InlineSpan("plain "),
                    InlineSpan("bold", setOf(InlineStyle.STRONG)),
                )),
                ContentBlock.Image("images/x.jpg", "caption"),
            ),
            startCharOffset = 40, charCount = 22,
        )
        s.writeChapters("b1", listOf(original))
        assertEquals(original, s.readChapter("b1", 2))
    }

    @Test
    fun `reading a missing chapter returns null rather than throwing`() {
        assertNull(store().readChapter("nope", 7))
    }

    @Test
    fun `reading a corrupt chapter file returns null rather than throwing`() {
        val s = store()
        s.writeChapters("b1", listOf(chapter(0, "fine")))
        File(s.bookDir("b1"), "chapters/000.json").writeText("{ not json")
        assertNull(s.readChapter("b1", 0))
    }

    @Test
    fun `reading one chapter does not require the others to exist`() {
        val s = store()
        s.writeChapters("b1", listOf(chapter(0, "a"), chapter(1, "b"), chapter(2, "c")))
        File(s.bookDir("b1"), "chapters/000.json").delete()
        File(s.bookDir("b1"), "chapters/001.json").delete()
        assertEquals("Chapter 2", s.readChapter("b1", 2)?.title)
    }

    @Test
    fun `delete removes the whole book directory`() {
        val s = store()
        s.writeOriginal("b1", "x".byteInputStream(), "txt")
        s.writeChapters("b1", listOf(chapter(0, "a")))
        s.writeCover("b1", byteArrayOf(1, 2, 3))

        s.delete("b1")
        assertFalse(File(temp.root, "books/b1").exists())
    }

    @Test
    fun `deleting a book that was never written is harmless`() {
        store().delete("never-existed")
    }

    @Test
    fun `deleting one book leaves the others alone`() {
        val s = store()
        s.writeChapters("keep", listOf(chapter(0, "a")))
        s.writeChapters("drop", listOf(chapter(0, "b")))
        s.delete("drop")
        assertEquals("Chapter 0", s.readChapter("keep", 0)?.title)
    }

    @Test
    fun `storage headroom accounts for the copy plus its normalized output`() {
        val s = store()
        assertTrue(s.freeBytes() > 0)
        assertFalse(s.hasRoomFor(Long.MAX_VALUE / 4))
        assertTrue(s.hasRoomFor(1024))
    }
}
