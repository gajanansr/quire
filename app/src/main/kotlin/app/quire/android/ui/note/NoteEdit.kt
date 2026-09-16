package app.quire.android.ui.note

import app.quire.core.reading.TextSpan

/**
 * A note being read or written, and everything needed to save it.
 *
 * Carries the span rather than leaning on the live selection: the sheet outlives the
 * gesture that opened it, and a note saved against "whatever is selected now" would
 * attach itself to the wrong characters the moment anything cleared it.
 *
 * [saved] is what was in the database when the sheet opened and never changes;
 * [text] is what the field holds. Every decision below is the difference between
 * the two, which is why neither is derived from the other.
 */
data class NoteDraft(
    val span: TextSpan,
    /** The passage itself, shown above the field so the note has something to be about. */
    val snippet: String,
    /** What was stored when the sheet opened. Empty means nobody has written here. */
    val saved: String = "",
    /** The row this belongs to, or null when the passage is not marked yet. */
    val bookmarkId: Long? = null,
    /** What the field holds now. Opens on [saved], so reading a note is not editing it. */
    val text: String = saved,
)

/** What saving a draft would actually do. */
enum class NoteOutcome {
    /** Nothing. The reader opened a note, read it, and backed out. */
    NOTHING,

    /** Store these words, marking the passage if it is not marked yet. */
    WRITE,

    /** Empty the note, leaving the mark and its colour where they are. */
    CLEAR,
}

/**
 * The decisions the note sheet makes, outside the sheet.
 *
 * There is no Compose test dependency here, so anything worth being sure of has to be
 * a function rather than a branch inside a composable. These are worth being sure of:
 * a note is the only value in Quire that cannot be reconstructed, and every one of
 * these rules exists to stop a stray tap taking one.
 */
object NoteEdit {

    /**
     * The words as they will be stored.
     *
     * Trimmed at the ends only. The blank lines inside a note are the reader's own
     * paragraphing, and collapsing them would be editing what they wrote.
     */
    fun tidy(text: String): String = text.trim()

    /**
     * What saving would do.
     *
     * Compared after tidying, because soft keyboards add a trailing newline or space
     * on their own: treating that as an edit would light Save up on a sheet nobody
     * typed in and rewrite a note with a copy of itself — and `createdAt` orders the
     * Bookmarks list, so a pointless write moves a mark for no reason anyone saw.
     *
     * The empty-to-empty case is [NOTHING] rather than a write, which is what stops
     * opening Note on a passage and changing your mind from leaving a highlight
     * behind. Writing a note marks the passage, deliberately — a note needs an anchor
     * — and this is the rule that keeps that from being a trap.
     */
    fun outcome(draft: NoteDraft): NoteOutcome {
        val now = tidy(draft.text)
        val before = tidy(draft.saved)
        return when {
            now == before -> NoteOutcome.NOTHING
            now.isEmpty() -> NoteOutcome.CLEAR
            else -> NoteOutcome.WRITE
        }
    }

    /**
     * Whether Save does anything.
     *
     * One rule, not two: a Save that is live on an unchanged sheet teaches the reader
     * it means nothing, and one that is dead on a changed sheet loses what they
     * typed.
     */
    fun canSave(draft: NoteDraft): Boolean = outcome(draft) != NoteOutcome.NOTHING

    /**
     * Whether there is a note to delete.
     *
     * Only ever offered on one that exists. A Delete on a blank sheet is a control
     * that cannot do anything, which is the same fault as a dead action on the
     * selection bar.
     */
    fun canDelete(draft: NoteDraft): Boolean = tidy(draft.saved).isNotEmpty()
}
