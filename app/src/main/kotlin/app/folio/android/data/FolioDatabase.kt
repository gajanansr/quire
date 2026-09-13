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

@Dao
interface HabitDao {
    @Query("SELECT * FROM reading_days ORDER BY epochDay")
    fun observeDays(): Flow<List<ReadingDayEntity>>

    @Query("SELECT * FROM reading_days WHERE epochDay = :day")
    suspend fun day(day: Long): ReadingDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(day: ReadingDayEntity)

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM reading_days")
    suspend fun totalMinutes(): Int

    @Query("SELECT COUNT(*) FROM reading_days WHERE minutes > 0")
    suspend fun daysRead(): Int
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 0")
    fun observe(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 0")
    suspend fun get(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(settings: AppSettingsEntity)
}

@Database(
    entities = [
        BookEntity::class, ReadingProgressEntity::class, BookmarkEntity::class,
        ReadingDayEntity::class, AppSettingsEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class FolioDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun progress(): ProgressDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun habits(): HabitDao
    abstract fun settings(): SettingsDao

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
        /**
         * Gives a bookmark an end, so it can be a highlight.
         *
         * Seeded from the start, which makes every existing bookmark a mark of no
         * width — exactly what it already was. Nothing is reinterpreted, and the
         * list keeps showing the same snippets it showed yesterday.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN endBlockIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN endCharOffset INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE bookmarks SET endBlockIndex = blockIndex, endCharOffset = charOffset")
            }
        }

        /**
         * Turns justification on for a reader who has one.
         *
         * A data migration rather than a schema one: the column already exists and
         * the default in [AppSettingsEntity] only reaches installs that have no row
         * yet. Without this, justification would be the default for new readers and
         * off for everyone already here, which is the kind of split that makes a
         * bug report impossible to reproduce. Safe because nothing in the app has
         * ever asked the question — the stored `false` is the previous default, not
         * an answer.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE app_settings SET readerJustify = 1")
            }
        }

        /** Adds the habit rollup and the single settings row. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reading_days (
                        epochDay INTEGER NOT NULL PRIMARY KEY,
                        minutes INTEGER NOT NULL,
                        goalMinutes INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_settings (
                        id INTEGER NOT NULL PRIMARY KEY,
                        dailyGoalMinutes INTEGER NOT NULL DEFAULT 10,
                        themeName TEXT NOT NULL DEFAULT 'LIGHT',
                        readerFont TEXT NOT NULL DEFAULT 'SERIF',
                        readerFontSizeSp REAL NOT NULL DEFAULT 19.0,
                        readerJustify INTEGER NOT NULL DEFAULT 0,
                        booksFinished INTEGER NOT NULL DEFAULT 0,
                        chaptersFinished INTEGER NOT NULL DEFAULT 0,
                        onboarded INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN subjects TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN description TEXT")
            }
        }
    }
}
