package app.quire.android.data

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

    /**
     * When the reader last turned a page, across the whole library.
     *
     * Nullable: no row means nobody has read anything yet. Written on every page
     * turn because that is when the position is saved, which makes it the freshest
     * evidence of reading there is — recorded minutes only appear when a session
     * ends.
     */
    @Query("SELECT MAX(updatedAt) FROM reading_progress")
    suspend fun lastUpdatedAt(): Long?
}

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(bookmark: BookmarkEntity): Long

    /**
     * The bookmark already covering exactly this span, if there is one.
     *
     * All six coordinates, not just the start: a bookmark is a highlight of no width,
     * so a highlight beginning where a bookmark sits is a different thing and must
     * not be mistaken for it.
     */
    @Query(
        """
        SELECT * FROM bookmarks
        WHERE bookId = :bookId
          AND chapterIndex = :chapterIndex
          AND blockIndex = :blockIndex AND charOffset = :charOffset
          AND endBlockIndex = :endBlockIndex AND endCharOffset = :endCharOffset
        LIMIT 1
        """
    )
    suspend fun existing(
        bookId: String,
        chapterIndex: Int,
        blockIndex: Int,
        charOffset: Int,
        endBlockIndex: Int,
        endCharOffset: Int,
    ): BookmarkEntity?

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
    version = 7,
    exportSchema = false,
)
abstract class QuireDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun progress(): ProgressDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun habits(): HabitDao
    abstract fun settings(): SettingsDao

    companion object {
        /**
         * Adds the flag that says the opening guide has been through.
         *
         * Seeded from `onboarded` rather than left at its default, and that line is
         * the whole point of the migration. The column's Kotlin default is `false`,
         * which is right for a fresh install and catastrophic for an upgrade: every
         * reader who has had Quire for months would open it after an update and be
         * greeted by a three-page tour of an app they already know. `guideSeen =
         * onboarded` says the true thing — anyone who got as far as choosing a goal
         * has already been introduced.
         *
         * Separate from `onboarded` going forward because the two answer different
         * questions. `onboarded` is only set once a goal is chosen; `guideSeen` is
         * set the moment the guide is finished or skipped, so a reader who closes
         * Quire in between is not shown the guide a second time.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN guideSeen INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE app_settings SET guideSeen = onboarded")
            }
        }

        /**
         * Adds the reminder settings.
         *
         * Every default here matches [AppSettingsEntity]'s, and the important one is
         * `remindersEnabled = 0`: an upgrade must not sign anyone up for
         * notifications. The reader's own tap is the only thing that sets it, which
         * is also what makes the permission request in Task 7 honest — by the time
         * Android asks, the reader has already said yes to Quire.
         *
         * `lastReminderDay = -1` is a sentinel that precedes every real epoch day,
         * so a freshly migrated install is not mistaken for one already reminded
         * today and silenced on its first evening.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN remindersEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN remindersAsked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN reminderMinuteOfDay INTEGER NOT NULL DEFAULT 1200")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN dailyReminderEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN streakReminderEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN reminderPermissionDenied INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN lastReminderDay INTEGER NOT NULL DEFAULT -1")
            }
        }

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
