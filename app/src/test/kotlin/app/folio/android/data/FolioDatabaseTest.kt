package app.folio.android.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FolioDatabaseTest {

    private lateinit var db: FolioDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), FolioDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun book(id: String, opened: Long? = null) = BookEntity(
        id = id, title = "Title $id", author = "Author", coverPath = null,
        sourceFormat = "EPUB", language = "en", publisher = null, identifier = null,
        subjects = "Essay|Design", description = "A study of quiet things.",
        totalChars = 1000, chapterCount = 3, reflowFailed = false,
        addedAt = 1000L, lastOpenedAt = opened,
    )

    @Test
    fun `round trips a book`() = runBlocking {
        db.books().upsert(book("a"))
        assertEquals("Title a", db.books().find("a")?.title)
    }

    @Test
    fun `deleting a book removes it`() = runBlocking {
        db.books().upsert(book("a"))
        db.books().delete("a")
        assertNull(db.books().find("a"))
    }

    @Test
    fun `progress round trips and replaces on conflict`() = runBlocking {
        db.books().upsert(book("a"))
        db.progress().save(ReadingProgressEntity("a", 1, 2, 3, 0.25, 1L))
        db.progress().save(ReadingProgressEntity("a", 4, 5, 6, 0.75, 2L))
        val p = db.progress().find("a")
        assertEquals(4, p?.chapterIndex)
        assertEquals(0.75, p?.progress ?: 0.0, 1e-9)
    }
}
