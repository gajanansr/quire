package app.folio.android.ui

import app.folio.core.model.FailureReason

/**
 * Every user-facing string, taken verbatim from the design handoff.
 *
 * Gathered in one place so the copy can be diffed against the handoff, and so no
 * enum name or technical term can leak into the interface by accident — the brief
 * is explicit that the user should never see the machinery.
 */
object FolioStrings {

    // Controls that are an icon on screen. A screen reader has nothing else to
    // announce for these, so the description is the only name they have.
    const val BACK = "Back"
    const val SHARE = "Share"
    const val BOOKMARK = "Bookmark"
    const val CONTENTS = "Contents"
    const val TYPOGRAPHY = "Type"
    const val FINISH = "Finish"

    // Library
    const val CONTINUE_READING = "Continue Reading"
    const val YOUR_BOOKS = "Your Books"
    const val LIBRARY_EMPTY = "Your library is empty."
    const val LIBRARY_EMPTY_HINT = "Add a book to get started."
    // The leading "+" is drawn by FolioIcons.Add now, not spelled in the copy.
    const val ADD_BOOK = "Add Book"

    // Habit card
    const val HABIT_STREAK = "7-day habit streak"
    const val HABIT_SUBTITLE = "Read every day this week"

    // Import
    const val ADD_BOOK_TITLE = "Add Book"
    const val CHOOSE_FROM_FILES = "Choose from Files"
    const val SUPPORTED_FORMATS = "EPUB · PDF · TXT"
    const val SUPPORTED_EXPLAINER = "Folio supports EPUB, PDF, and TXT files."
    const val PREPARING = "Preparing your book…"
    const val EXTRACTING_TEXT = "Extracting text"
    const val DETECTING_CHAPTERS = "Detecting chapters"
    const val BOOK_READY = "Your book is ready."
    const val READ_NOW = "Read now"

    // Errors
    const val ERROR_TITLE = "We couldn't open this file."
    const val TRY_ANOTHER_FILE = "Try another file"
    const val READ_ORIGINAL_PDF = "Read original PDF"

    // A scanned book is pictures of pages, not text. Said plainly, and said as a
    // fact about the file rather than as an apology or an error: nothing has gone
    // wrong, and the reader has not lost anything they ever had.
    const val ORIGINAL_PAGES = "Original pages"
    const val SCANNED_TITLE = "This book is a scan."
    const val SCANNED_EXPLAINER =
        "Its pages are images, not text, so Folio shows them as they were printed. " +
            "Font size, themes and chapters aren't available for this one."
    const val READ_SCANNED_PAGES = "Read the pages"

    // Bookmarks
    const val BOOKMARKS = "Bookmarks"
    const val NO_BOOKMARKS = "No bookmarks yet."
    const val NO_BOOKMARKS_HINT =
        "Highlights and bookmarks you save while reading will appear here."

    // Reminders. The lines a *delivered* reminder carries are not here: they vary
    // by kind and by day and are chosen by a rule, so they live beside that rule in
    // notify/ReminderWords.kt where the tests that police them can reach them.
    // These are the fixed strings — what the channel is called in system settings,
    // and what the reader is asked and shown in Folio itself.
    const val REMINDER_CHANNEL = "Reading reminders"
    const val REMINDER_CHANNEL_EXPLAINER =
        "One gentle reminder a day, and none at all on a day you've already read."

    // The offer, made once, after a reading session that recorded real minutes.
    // It states the two things a reader needs to decide: how often, and that a day
    // they have already read is a day Folio says nothing.
    const val REMINDER_INVITE_TITLE = "Want a nudge at reading time?"
    const val REMINDER_INVITE_BODY =
        "One quiet reminder a day, at a time you pick. Never on a day you've " +
            "already read, and you can turn it off whenever you like."
    const val REMINDER_INVITE_YES = "Yes, remind me"
    const val REMINDER_INVITE_NO = "No thanks"

    // Settings.
    const val REMINDERS = "Reading reminders"
    const val REMINDER_TIME = "Remind me at"
    const val REMINDER_DAILY = "Daily reminder"
    const val REMINDER_STREAK = "Streak nudges"
    const val REMINDERS_BLOCKED = "Turned off in system settings"
    const val REMINDERS_ON = "On"
    const val REMINDERS_OFF = "Off"

    // Navigation
    const val NAV_LIBRARY = "Library"
    const val NAV_BOOKMARKS = "Bookmarks"
    const val NAV_SETTINGS = "Settings"

    // Leaving. Back means "out of this"; only the last one out means "done", and
    // that one asks — closing a reading app by accident loses the thread, not data.
    const val CLOSE_FOLIO = "Close Folio?"
    const val CLOSE_FOLIO_HINT = "Your place is saved. You can pick it up whenever."
    const val CLOSE = "Close"
    const val KEEP_READING = "Stay"

    // Sharing. The destinations name what happens, not which app it happens in —
    // the system chooser is where an app gets picked. SHARE is declared above, with
    // the other icon labels.
    const val SHARE_IMAGE = "Image"
    const val SHARE_TEXT = "Text"
    const val COPY = "Copy"
    const val SAVE = "Save"
    const val COPIED = "Copied"
    const val ADD_A_CAPTION = "Add a caption\u2026"
    const val HIGHLIGHT = "Highlight"
    const val REMOVE = "Remove"

    /**
     * Greeting by time of day. The handoff shows "Good evening"; the others follow
     * the same voice.
     */
    fun greeting(hourOfDay: Int): String = when (hourOfDay) {
        in 0..4 -> "Good evening"
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    /**
     * A failure the user can act on. Deliberately never exposes the reason's name:
     * "EXTRACTION_FAILED" is machinery, not an explanation.
     */
    fun explain(reason: FailureReason): String = when (reason) {
        FailureReason.CORRUPT_FILE -> "This file seems to be damaged."
        FailureReason.UNSUPPORTED_FORMAT -> SUPPORTED_EXPLAINER
        FailureReason.ENCRYPTED -> "This file is password protected."
        FailureReason.EMPTY_DOCUMENT -> "This file doesn't contain any text."
        FailureReason.EXTRACTION_FAILED -> "Something went wrong reading this file."
        FailureReason.OCR_FAILED -> "The text in this file was too unclear to read."
        FailureReason.INSUFFICIENT_STORAGE -> "There isn't enough space left on your device."
        FailureReason.INTERRUPTED -> "Importing stopped before it finished."
    }

    /** The import stage, in the handoff's words rather than the pipeline's. */
    fun importStage(stage: String): String = when (stage) {
        "importing", "detectingFormat" -> PREPARING
        "extracting", "ocr" -> EXTRACTING_TEXT
        "detectingStructure" -> DETECTING_CHAPTERS
        "normalizing" -> PREPARING
        "ready" -> BOOK_READY
        else -> PREPARING
    }
}
