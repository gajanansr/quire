package app.folio.android.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings migrations, run against real SQLite.
 *
 * Room's own `MigrationTestHelper` needs exported schemas, and this database sets
 * `exportSchema = false`. Running the migration's SQL against an in-memory database
 * seeded by hand tests the same thing — whether the statements do what they claim —
 * without committing a schema directory that would then need maintaining.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsMigrationTest {

    /** An in-memory database holding the version-3 settings table and one row. */
    private fun versionThree(justify: Int): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(ApplicationProvider.getApplicationContext())
                // A null name is an in-memory database: nothing to clean up, and no
                // chance of one test's file reaching another.
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase
        db.execSQL(
            """
            CREATE TABLE app_settings (
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
        db.execSQL(
            """
            INSERT INTO app_settings
                (id, dailyGoalMinutes, themeName, readerFont, readerFontSizeSp,
                 readerJustify, booksFinished, chaptersFinished, onboarded)
            VALUES (0, 25, 'EINK', 'LORA', 22.0, $justify, 2, 9, 1)
            """.trimIndent()
        )
        return db
    }

    private fun <T> SupportSQLiteDatabase.one(sql: String, read: (android.database.Cursor) -> T): T =
        query(sql).use { it.moveToFirst(); read(it) }

    @Test
    fun `justification becomes the default for a reader who never chose`() {
        // The stored false is the old default, not a decision anyone made — nothing
        // in the app has ever asked. Leaving it would mean the setting changed for
        // new installs only, which is the sort of split nobody can reason about.
        val db = versionThree(justify = 0)
        FolioDatabase.MIGRATION_3_4.migrate(db)
        assertEquals(1, db.one("SELECT readerJustify FROM app_settings WHERE id = 0") { it.getInt(0) })
        db.close()
    }

    @Test
    fun `the rest of the settings row survives the migration`() {
        val db = versionThree(justify = 0)
        FolioDatabase.MIGRATION_3_4.migrate(db)
        db.query("SELECT dailyGoalMinutes, themeName, readerFont, readerFontSizeSp, onboarded FROM app_settings")
            .use {
                it.moveToFirst()
                assertEquals(25, it.getInt(0))
                assertEquals("EINK", it.getString(1))
                assertEquals("LORA", it.getString(2))
                assertEquals(22.0f, it.getFloat(3), 0.001f)
                assertEquals(1, it.getInt(4))
            }
        db.close()
    }

    @Test
    fun `a default settings row is justified`() {
        assertEquals(true, AppSettingsEntity().readerJustify)
    }

    @Test
    fun `an existing bookmark becomes a highlight of no width`() {
        // Nothing is reinterpreted by giving bookmarks an end. Every row already in
        // the table marked a place, and a place is a highlight that covers no words.
        val db = versionThree(justify = 0)
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                bookId TEXT NOT NULL,
                chapterIndex INTEGER NOT NULL,
                blockIndex INTEGER NOT NULL,
                charOffset INTEGER NOT NULL,
                snippet TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO bookmarks (bookId, chapterIndex, blockIndex, charOffset, snippet, createdAt) " +
                "VALUES ('b1', 7, 4, 213, 'A saved place', 100)"
        )

        FolioDatabase.MIGRATION_4_5.migrate(db)

        db.query("SELECT blockIndex, charOffset, endBlockIndex, endCharOffset FROM bookmarks").use {
            it.moveToFirst()
            assertEquals(4, it.getInt(0))
            assertEquals(213, it.getInt(1))
            assertEquals("the end did not follow the start", 4, it.getInt(2))
            assertEquals("the end did not follow the start", 213, it.getInt(3))
        }
        db.close()
    }

    // ------------------------------------------------- reminders (5 -> 6)

    /**
     * The version-5 settings table.
     *
     * Identical to version 3's: `MIGRATION_3_4` only rewrote a value and
     * `MIGRATION_4_5` only touched bookmarks, so `app_settings` has not changed
     * shape since it was created.
     */
    private fun versionFive(): SupportSQLiteDatabase = versionThree(justify = 1)

    @Test
    fun `an upgrading reader is not signed up for notifications`() {
        // The worst possible introduction to this feature is a phone that starts
        // buzzing after an update nobody asked for. The migration creates the
        // column; the reader's own tap is the only thing that can set it.
        val db = versionFive()
        FolioDatabase.MIGRATION_5_6.migrate(db)
        assertEquals(
            "an upgrade enabled reminders on its own",
            0,
            db.one("SELECT remindersEnabled FROM app_settings WHERE id = 0") { it.getInt(0) },
        )
        assertEquals(
            "an upgrade recorded that the reader had already been asked",
            0,
            db.one("SELECT remindersAsked FROM app_settings WHERE id = 0") { it.getInt(0) },
        )
        db.close()
    }

    @Test
    fun `the reminder defaults are the ones a reader would expect`() {
        val db = versionFive()
        FolioDatabase.MIGRATION_5_6.migrate(db)
        db.query(
            "SELECT reminderMinuteOfDay, dailyReminderEnabled, streakReminderEnabled, " +
                "reminderPermissionDenied, lastReminderDay FROM app_settings WHERE id = 0"
        ).use {
            it.moveToFirst()
            assertEquals("the default reminder time is not 8pm", 20 * 60, it.getInt(0))
            assertEquals("the daily reminder is not on by default", 1, it.getInt(1))
            assertEquals("streak nudges are not on by default", 1, it.getInt(2))
            assertEquals("permission is presumed denied", 0, it.getInt(3))
            // Before every real epoch day, so a fresh install is never mistaken for
            // one that has already been reminded today.
            assertEquals("lastReminderDay does not precede every real day", -1, it.getInt(4))
        }
        db.close()
    }

    @Test
    fun `nothing the reader already chose is lost on the way to version 6`() {
        val db = versionFive()
        FolioDatabase.MIGRATION_5_6.migrate(db)
        db.query(
            "SELECT dailyGoalMinutes, themeName, readerFont, readerFontSizeSp, " +
                "readerJustify, booksFinished, chaptersFinished, onboarded FROM app_settings"
        ).use {
            it.moveToFirst()
            assertEquals(25, it.getInt(0))
            assertEquals("EINK", it.getString(1))
            assertEquals("LORA", it.getString(2))
            assertEquals(22.0f, it.getFloat(3), 0.001f)
            assertEquals(1, it.getInt(4))
            assertEquals(2, it.getInt(5))
            assertEquals(9, it.getInt(6))
            assertEquals(1, it.getInt(7))
        }
        db.close()
    }

    @Test
    fun `a fresh install and an upgraded one agree about reminders`() {
        // Two descriptions of the same defaults — the Kotlin one for fresh installs,
        // the SQL one for upgrades. They drift apart silently, and then the same app
        // behaves differently depending on when it happened to be installed.
        val defaults = AppSettingsEntity()
        assertEquals("a fresh install has reminders on", false, defaults.remindersEnabled)
        assertEquals("a fresh install has already been asked", false, defaults.remindersAsked)
        assertEquals(20 * 60, defaults.reminderMinuteOfDay)
        assertEquals(true, defaults.dailyReminderEnabled)
        assertEquals(true, defaults.streakReminderEnabled)
        assertEquals(false, defaults.reminderPermissionDenied)
        assertEquals(-1L, defaults.lastReminderDay)
    }
}
