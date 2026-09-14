package app.quire.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Library metadata. Chapter content lives on disk, one file per chapter, so a book
 * row stays small and the reader can load a chapter without touching the rest.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val sourceFormat: String,
    val language: String?,
    val publisher: String?,
    val identifier: String?,
    /** dc:subject values joined with "|" — Room stores no lists natively. */
    val subjects: String,
    val description: String?,
    val totalChars: Int,
    val chapterCount: Int,
    /** Reflow was not confident; the UI offers the original file instead. */
    val reflowFailed: Boolean,
    val addedAt: Long,
    val lastOpenedAt: Long?,
)

/** One row per book: where the reader resumes. */
@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int,
    val blockIndex: Int,
    val charOffset: Int,
    val progress: Double,
    val updatedAt: Long,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val blockIndex: Int,
    val charOffset: Int,
    /**
     * Where the marked passage ends.
     *
     * A plain bookmark ends where it starts — one table holds both, because a
     * bookmark is simply a highlight of no width, and splitting them would mean two
     * lists, two queries and two places to forget one of them.
     */
    val endBlockIndex: Int = blockIndex,
    val endCharOffset: Int = charOffset,
    /** Snapshot of the text, so a bookmark survives reprocessing. */
    val snippet: String,
    val createdAt: Long,
) {
    /** True when the reader chose words, rather than marking a place. */
    val isHighlight: Boolean
        get() = endBlockIndex > blockIndex ||
            (endBlockIndex == blockIndex && endCharOffset > charOffset)
}

/**
 * One day's reading total, keyed by local epoch-day.
 *
 * Stored as a rollup rather than derived from sessions on every read: the Library
 * card and the streak heatmap want it on every launch, and re-summing a year of
 * sessions each time would be wasteful.
 */
@Entity(tableName = "reading_days")
data class ReadingDayEntity(
    @PrimaryKey val epochDay: Long,
    val minutes: Int,
    /** The goal in force that day, so history is not rewritten by changing it. */
    val goalMinutes: Int,
)

/** One row, id 0. Small enough that a table beats a preferences file. */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 0,
    val dailyGoalMinutes: Int = 10,
    val themeName: String = "PAPER",
    val readerFont: String = "SERIF",
    val readerFontSizeSp: Float = 19f,
    /**
     * Justified by default. Books are set justified; a ragged right edge is the web's
     * convention, not the page's, and [MIGRATION_3_4] carries existing readers over
     * because the stored `false` was an old default rather than anyone's choice.
     */
    val readerJustify: Boolean = true,
    val booksFinished: Int = 0,
    val chaptersFinished: Int = 0,
    val onboarded: Boolean = false,
    /**
     * Whether the reader has been through the opening guide.
     *
     * Separate from [onboarded], which is only set once a goal has been chosen. A
     * reader who read the guide and then closed Quire before the goal picker would
     * otherwise be shown the whole thing again on their next launch, and "never
     * shown twice" would be true only for people who finished the flow in one
     * sitting.
     *
     * [MIGRATION_6_7] seeds it from `onboarded`, so nobody already using Quire is
     * greeted by a tour of an app they have had for months.
     */
    val guideSeen: Boolean = false,

    // ------------------------------------------------------------- reminders

    /**
     * The master switch, off until the reader says otherwise.
     *
     * Off is the default for new installs and for upgrades alike. Notifications are
     * the one feature where shipping the useful default is the wrong call: a phone
     * that starts buzzing after an update nobody asked for teaches the reader to
     * silence Quire, and that lesson does not wear off.
     */
    val remindersEnabled: Boolean = false,
    /**
     * Whether the reader has been offered reminders in the app.
     *
     * Set by either answer. The offer appears once, after a reading session that
     * recorded real minutes, and a "no thanks" has to be as permanent as a yes or it
     * is not a choice at all.
     */
    val remindersAsked: Boolean = false,
    /** Minutes past local midnight. 20:00 — after dinner, before bed. */
    val reminderMinuteOfDay: Int = 20 * 60,
    val dailyReminderEnabled: Boolean = true,
    val streakReminderEnabled: Boolean = true,
    /**
     * The reader refused the system prompt. Android shows it at most twice and then
     * silently refuses; asking again would be both useless and rude, so once this is
     * set the only route offered is the system settings screen.
     */
    val reminderPermissionDenied: Boolean = false,
    /**
     * The local epoch-day a reminder was last delivered, or -1 for never.
     *
     * -1 rather than 0 because epoch day 0 is a real date (1 Jan 1970); a sentinel
     * that is also a valid value would make a fresh install look as though it had
     * already been reminded.
     */
    val lastReminderDay: Long = -1L,
)
