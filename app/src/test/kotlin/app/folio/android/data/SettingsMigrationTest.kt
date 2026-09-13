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
}
