package app.folio.android.data

import androidx.room.Database
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Query("SELECT * FROM books ORDER BY COALESCE(lastOpenedAt, addedAt) DESC")
    fun observeLibrary(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun find(id: String): BookEntity?

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE books SET lastOpenedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    /**
     * The Library needs progress alongside metadata. Joining in SQL keeps it to one
     * query and one emission, rather than a per-book lookup that would re-render the
     * grid as each result arrived.
     */
    @Query(
        """
        SELECT b.*, p.progress AS progress
        FROM books b
        LEFT JOIN reading_progress p ON p.bookId = b.id
        ORDER BY COALESCE(b.lastOpenedAt, b.addedAt) DESC
        """
    )
    fun observeLibraryWithProgress(): Flow<List<LibraryRow>>
}

/** A book row with its reading progress joined in. */
data class LibraryRow(
    val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val sourceFormat: String,
    val language: String?,
    val publisher: String?,
    val identifier: String?,
    val subjects: String,
    val description: String?,
    val totalChars: Int,
    val chapterCount: Int,
    val reflowFailed: Boolean,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val progress: Double?,
)

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun find(bookId: String): ReadingProgressEntity?

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId")
    suspend fun deleteFor(bookId: String)
}

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex, blockIndex")
    fun observeFor(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun remove(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteFor(bookId: String)
}

@Database(
    entities = [BookEntity::class, ReadingProgressEntity::class, BookmarkEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class FolioDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun progress(): ProgressDao
    abstract fun bookmarks(): BookmarkDao

    companion object {
        /**
         * Adds the fields Book Details needs: the publisher's description and the
         * dc:subject values shown as genre chips.
         *
         * A real migration rather than a destructive fallback even though nothing
         * has shipped. Destructive fallback deletes a reader's whole library on a
         * schema change, and it is far too easy to leave in place until it does
         * exactly that to someone.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN subjects TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN description TEXT")
            }
        }
    }
}
