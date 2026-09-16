package app.quire.android.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.quire.android.ui.theme.HighlightColour
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
        QuireDatabase.MIGRATION_3_4.migrate(db)
        assertEquals(1, db.one("SELECT readerJustify FROM app_settings WHERE id = 0") { it.getInt(0) })
        db.close()
    }

    @Test
    fun `the rest of the settings row survives the migration`() {
        val db = versionThree(justify = 0)
        QuireDatabase.MIGRATION_3_4.migrate(db)
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

        QuireDatabase.MIGRATION_4_5.migrate(db)

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
        QuireDatabase.MIGRATION_5_6.migrate(db)
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
        QuireDatabase.MIGRATION_5_6.migrate(db)
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
        QuireDatabase.MIGRATION_5_6.migrate(db)
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

    // ------------------------------------------- the opening guide (6 -> 7)

    /** A version-6 settings table: version five plus the seven reminder columns. */
    private fun versionSix(onboarded: Int): SupportSQLiteDatabase {
        val db = versionThree(justify = 1)
        db.execSQL("UPDATE app_settings SET onboarded = $onboarded")
        QuireDatabase.MIGRATION_5_6.migrate(db)
        return db
    }

    @Test
    fun `an existing reader is not greeted by a tour of an app they already use`() {
        // The whole reason this migration does more than add a column. `guideSeen`
        // defaults to 0, which is right for a fresh install and wrong for everyone
        // already here: without the seeding line, shipping the guide would show it
        // to every reader who has had Quire for months, on the launch straight after
        // an update they did not ask for.
        val db = versionSix(onboarded = 1)
        QuireDatabase.MIGRATION_6_7.migrate(db)
        assertEquals(
            "an upgrade queued the opening guide for a reader who is already onboarded",
            1,
            db.one("SELECT guideSeen FROM app_settings WHERE id = 0") { it.getInt(0) },
        )
        db.close()
    }

    @Test
    fun `a reader who never finished onboarding still gets the guide`() {
        // The other half of the same rule. Somebody who installed Quire, saw the
        // welcome screen and closed it has not been introduced to anything, and
        // seeding `guideSeen` from `onboarded` is what keeps that true rather than
        // blanket-marking every existing row as done.
        val db = versionSix(onboarded = 0)
        QuireDatabase.MIGRATION_6_7.migrate(db)
        assertEquals(
            "a half-onboarded reader had the guide marked as already seen",
            0,
            db.one("SELECT guideSeen FROM app_settings WHERE id = 0") { it.getInt(0) },
        )
        db.close()
    }

    @Test
    fun `a fresh install has not seen the guide`() {
        assertEquals(false, AppSettingsEntity().guideSeen)
    }

    @Test
    fun `nothing the reader already chose is lost on the way to version 7`() {
        val db = versionSix(onboarded = 1)
        QuireDatabase.MIGRATION_6_7.migrate(db)
        db.query(
            "SELECT dailyGoalMinutes, themeName, readerFont, remindersEnabled, " +
                "reminderMinuteOfDay, lastReminderDay FROM app_settings WHERE id = 0"
        ).use {
            it.moveToFirst()
            assertEquals(25, it.getInt(0))
            assertEquals("EINK", it.getString(1))
            assertEquals("LORA", it.getString(2))
            assertEquals(0, it.getInt(3))
            assertEquals(20 * 60, it.getInt(4))
            assertEquals(-1, it.getInt(5))
        }
        db.close()
    }

    @Test
    fun `the version 7 table is the one Room expects to find`() {
        // The same guard as the version-6 test below, for the same reason: Room
        // validates the schema after a migration and an unexpected column is an
        // IllegalStateException at launch on every upgrading device. Compared
        // against what Room builds from the entity rather than a literal list, so a
        // column added to `AppSettingsEntity` and forgotten here fails without
        // anyone having to remember this file exists.
        val room = androidx.room.Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), QuireDatabase::class.java,
        ).allowMainThreadQueries().build()
        val expected = columnsOf(room.openHelper.writableDatabase, "app_settings")
        room.close()

        val migrated = versionSix(onboarded = 1)
        QuireDatabase.MIGRATION_6_7.migrate(migrated)
        val actual = columnsOf(migrated, "app_settings")
        migrated.close()

        assertEquals(
            "the migrated app_settings does not match the one Room builds from the entity",
            expected,
            actual,
        )
    }

    @Test
    fun `the migrated table is the one Room expects to find`() {
        // The failure this prevents is the worst in the file: Room validates the
        // schema after running a migration, and a column it did not expect — a typo
        // in a name, INTEGER where it wanted TEXT, a nullable where it wanted NOT
        // NULL — is an IllegalStateException at launch on every device upgrading
        // from the previous version. Nothing else here would catch it, because every
        // other test in this class runs the migration's SQL without ever asking Room
        // whether it approves.
        //
        // Compared rather than asserted literally: Room builds `app_settings` from
        // [AppSettingsEntity], so a column added to the entity and forgotten in the
        // migration fails here without anyone having to remember to update a list.
        //
        // Run as the whole chain a version-5 install actually takes, rather than
        // stopping at 6. Room validates once, at the end, so the chain is the unit
        // that has to match — and a device two versions behind is exactly the one
        // nobody tests by hand.
        val room = androidx.room.Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), QuireDatabase::class.java,
        ).allowMainThreadQueries().build()
        val expected = columnsOf(room.openHelper.writableDatabase, "app_settings")
        room.close()

        val migrated = versionFive()
        QuireDatabase.MIGRATION_5_6.migrate(migrated)
        QuireDatabase.MIGRATION_6_7.migrate(migrated)
        val actual = columnsOf(migrated, "app_settings")
        migrated.close()

        assertEquals(
            "the migrated app_settings does not match the one Room builds from the entity",
            expected,
            actual,
        )
    }

    // ------------------------------------------ highlight colour (7 -> 8)

    /**
     * A version-7 bookmarks table, with a highlight and a plain bookmark in it.
     *
     * Shaped as `MIGRATION_4_5` leaves it: the two end columns were the last thing to
     * happen to this table.
     */
    private fun versionSevenBookmarks(): SupportSQLiteDatabase {
        val db = versionSix(onboarded = 1)
        QuireDatabase.MIGRATION_6_7.migrate(db)
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                bookId TEXT NOT NULL,
                chapterIndex INTEGER NOT NULL,
                blockIndex INTEGER NOT NULL,
                charOffset INTEGER NOT NULL,
                endBlockIndex INTEGER NOT NULL DEFAULT 0,
                endCharOffset INTEGER NOT NULL DEFAULT 0,
                snippet TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX index_bookmarks_bookId ON bookmarks (bookId)")
        // A highlight: it covers words.
        db.execSQL(
            "INSERT INTO bookmarks (bookId, chapterIndex, blockIndex, charOffset, " +
                "endBlockIndex, endCharOffset, snippet, createdAt) " +
                "VALUES ('b1', 3, 7, 120, 7, 186, 'The words they kept.', 500)"
        )
        // A plain bookmark: it marks a place, so it has no width.
        db.execSQL(
            "INSERT INTO bookmarks (bookId, chapterIndex, blockIndex, charOffset, " +
                "endBlockIndex, endCharOffset, snippet, createdAt) " +
                "VALUES ('b1', 4, 0, 0, 0, 0, 'A saved place', 600)"
        )
        return db
    }

    @Test
    fun `every existing highlight becomes the colour it already was`() {
        // Gold is not a guess. It is the only colour a highlight has ever been drawn
        // in, so seeding every row with it means the update that brings colours to
        // the app changes nothing on anybody's page — which is the whole reason this
        // is a migration rather than a Kotlin default that reaches new rows only.
        val db = versionSevenBookmarks()
        QuireDatabase.MIGRATION_7_8.migrate(db)
        db.query("SELECT highlightColour FROM bookmarks ORDER BY createdAt").use {
            it.moveToFirst()
            assertEquals("an existing highlight lost its colour", "KEEP", it.getString(0))
            it.moveToNext()
            assertEquals("a plain bookmark has no colour to fall back on", "KEEP", it.getString(0))
        }
        db.close()
    }

    @Test
    fun `a migrated highlight still covers the same words`() {
        // The coordinates are the highlight. Six numbers decide which characters light
        // up, and a column added beside them must disturb none of them — a shift here
        // paints the mark over the wrong words, which reads as a rendering bug rather
        // than as lost data and so gets reported as one.
        val db = versionSevenBookmarks()
        QuireDatabase.MIGRATION_7_8.migrate(db)
        db.query(
            "SELECT bookId, chapterIndex, blockIndex, charOffset, endBlockIndex, " +
                "endCharOffset, snippet, createdAt FROM bookmarks ORDER BY createdAt"
        ).use {
            it.moveToFirst()
            assertEquals("b1", it.getString(0))
            assertEquals(3, it.getInt(1))
            assertEquals(7, it.getInt(2))
            assertEquals(120, it.getInt(3))
            assertEquals(7, it.getInt(4))
            assertEquals(186, it.getInt(5))
            assertEquals("The words they kept.", it.getString(6))
            assertEquals(500, it.getInt(7))
        }
        db.close()
    }

    @Test
    fun `the migrated bookmarks table is the one Room expects to find`() {
        // Room validates the schema after a migration, and a column it did not expect
        // is an IllegalStateException at launch on every upgrading device. Compared
        // against what Room builds from BookmarkEntity rather than against a literal
        // list, so a column added to the entity and forgotten here fails without
        // anyone having to remember this file exists.
        //
        // Run as the whole chain a version-7 install actually takes, not one step of
        // it. Room validates once, at the end, so the chain is the unit that has to
        // match — and every new migration lengthens it. This is the test that caught
        // `note` arriving in the entity, and it is the one to extend next time.
        val room = androidx.room.Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), QuireDatabase::class.java,
        ).allowMainThreadQueries().build()
        val expected = columnsOf(room.openHelper.writableDatabase, "bookmarks")
        room.close()

        val migrated = versionSevenBookmarks()
        QuireDatabase.MIGRATION_7_8.migrate(migrated)
        QuireDatabase.MIGRATION_8_9.migrate(migrated)
        val actual = columnsOf(migrated, "bookmarks")
        migrated.close()

        assertEquals(
            "the migrated bookmarks does not match the one Room builds from the entity",
            expected,
            actual,
        )
    }

    @Test
    fun `a fresh row and a migrated one agree about colour`() {
        // Two descriptions of the same default — the Kotlin one for rows written from
        // now on, the SQL one for every row already saved. They drift apart silently,
        // and then a highlight made before the update and one made after are different
        // colours for no reason the reader can see.
        val fresh = BookmarkEntity(
            bookId = "b1", chapterIndex = 0, blockIndex = 0, charOffset = 0,
            snippet = "", createdAt = 0,
        )
        assertEquals(HighlightColour.DEFAULT.name, fresh.highlightColour)
    }

    // ------------------------------------ the reader's own words (8 -> 9)

    /** A version-8 bookmarks table: version seven plus the colour column. */
    private fun versionEightBookmarks(): SupportSQLiteDatabase {
        val db = versionSevenBookmarks()
        QuireDatabase.MIGRATION_7_8.migrate(db)
        return db
    }

    @Test
    fun `every existing mark gains an empty note rather than a null one`() {
        // Empty string, not null, and the default is what makes that safe: every row
        // already in the table is a passage nobody has written about yet, and "no
        // note" is a real state rather than a missing value. A nullable column would
        // put a null check in front of every later read of it, and the first one
        // forgotten is a crash in the Bookmarks list.
        val db = versionEightBookmarks()
        QuireDatabase.MIGRATION_8_9.migrate(db)
        db.query("SELECT note FROM bookmarks ORDER BY createdAt").use {
            it.moveToFirst()
            assertEquals("a highlight came out of the migration with no note", "", it.getString(0))
            it.moveToNext()
            assertEquals("a plain bookmark came out with no note", "", it.getString(0))
        }
        db.close()
    }

    @Test
    fun `a migrated mark still covers the same words, in the same colour`() {
        // The same guard MIGRATION_7_8 has, for the same reason: six numbers decide
        // which characters light up, and a column added beside them must disturb none
        // of them. A shift here paints marks over the wrong words, which reads as a
        // rendering bug and gets reported as one.
        val db = versionEightBookmarks()
        db.execSQL("UPDATE bookmarks SET highlightColour = 'DOUBT' WHERE createdAt = 500")
        QuireDatabase.MIGRATION_8_9.migrate(db)
        db.query(
            "SELECT bookId, chapterIndex, blockIndex, charOffset, endBlockIndex, " +
                "endCharOffset, snippet, highlightColour, createdAt FROM bookmarks " +
                "ORDER BY createdAt"
        ).use {
            it.moveToFirst()
            assertEquals("b1", it.getString(0))
            assertEquals(3, it.getInt(1))
            assertEquals(7, it.getInt(2))
            assertEquals(120, it.getInt(3))
            assertEquals(7, it.getInt(4))
            assertEquals(186, it.getInt(5))
            assertEquals("The words they kept.", it.getString(6))
            assertEquals("a mark lost the colour the reader chose", "DOUBT", it.getString(7))
            assertEquals(500, it.getInt(8))
        }
        db.close()
    }

    // The shape Room ends up with is checked once, over the whole chain, by
    // `the migrated bookmarks table is the one Room expects to find` above. It is
    // deliberately not repeated per version: Room validates once, at the end, so the
    // chain is the unit that has to match — and that test failing is exactly how the
    // `note` column announced itself here.

    @Test
    fun `a fresh row and a migrated one agree that there is no note`() {
        // The Kotlin default reaches rows written from now on; the SQL default
        // reaches every row already saved. They drift apart silently, and then a
        // passage marked before the update and one marked after disagree about what
        // "no note" is.
        val fresh = BookmarkEntity(
            bookId = "b1", chapterIndex = 0, blockIndex = 0, charOffset = 0,
            snippet = "", createdAt = 0,
        )
        assertEquals("", fresh.note)
    }

    /**
     * A table's shape, as SQLite reports it.
     *
     * Names, types, nullability and primary key — but deliberately not the SQL
     * default, because Room emits none for a Kotlin default and the migration must.
     */
    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info($table)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        "${cursor.getString(1)} ${cursor.getString(2)} " +
                            "notNull=${cursor.getInt(3)} pk=${cursor.getInt(5)}"
                    )
                }
            }.sorted()
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
