package app.quire.android.ui.note

import app.quire.core.reading.TextAnchor
import app.quire.core.reading.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What closing the note sheet actually does.
 *
 * There is no Compose test dependency in this project, so the decisions the sheet
 * makes live here instead of inside it: whether Save does anything, whether it writes
 * or clears, and whether the passage ends up marked. Getting any of those wrong loses
 * the reader's own words, which is the one thing in this app that cannot be
 * reconstructed.
 */
class NoteEditTest {

    private val span = TextSpan(TextAnchor(7, 120), TextAnchor(7, 186))

    private fun draft(saved: String = "", text: String = saved, id: Long? = null) =
        NoteDraft(span = span, snippet = "The words they kept.", saved = saved, bookmarkId = id, text = text)

    @Test
    fun `a sheet nobody typed in does nothing`() {
        // The common exit: the reader opened the note, read it, and backed out. It
        // must not rewrite the row — `createdAt` orders the Bookmarks list, and a
        // no-op write that reordered it would move a mark for no reason anyone saw.
        assertEquals(NoteOutcome.NOTHING, NoteEdit.outcome(draft(saved = "A thought.", id = 3)))
    }

    @Test
    fun `an empty sheet backed out of marks nothing`() {
        // Opening Note on a passage and changing your mind must not leave a highlight
        // behind. Writing a note marks the passage — a note needs an anchor — so the
        // rule that stops that being a trap is here: no words, no mark.
        assertEquals(NoteOutcome.NOTHING, NoteEdit.outcome(draft()))
    }

    @Test
    fun `the whitespace a keyboard leaves behind is not a change`() {
        // Soft keyboards add a trailing newline or space on their own. Treating that
        // as an edit would make Save light up on a sheet nobody typed in, and would
        // rewrite a note with a version of itself.
        assertEquals(
            NoteOutcome.NOTHING,
            NoteEdit.outcome(draft(saved = "A thought.", text = "A thought.\n ", id = 3)),
        )
    }

    @Test
    fun `words on a passage nobody has written about are a write`() {
        assertEquals(NoteOutcome.WRITE, NoteEdit.outcome(draft(text = "Mistrust this.")))
    }

    @Test
    fun `emptying a note clears it rather than writing an empty one`() {
        // Two different writes at the repository. The distinction exists because the
        // path that saves a note refuses to carry a blank one — that guard is what
        // protects a note from a later tap on Highlight — so clearing has to be said
        // in its own words.
        assertEquals(
            NoteOutcome.CLEAR,
            NoteEdit.outcome(draft(saved = "A thought.", text = "   ", id = 3)),
        )
    }

    @Test
    fun `Save is offered exactly when saving would do something`() {
        // One rule, not two. A Save button that is live on an unchanged sheet teaches
        // the reader it means nothing; one that is dead on a changed sheet loses what
        // they typed.
        listOf(
            draft(),
            draft(saved = "A thought.", id = 3),
            draft(text = "Mistrust this."),
            draft(saved = "A thought.", text = "", id = 3),
            draft(saved = "A thought.", text = "A better one.", id = 3),
        ).forEach {
            assertEquals(
                "Save disagrees with what saving would do: $it",
                NoteEdit.outcome(it) != NoteOutcome.NOTHING,
                NoteEdit.canSave(it),
            )
        }
    }

    @Test
    fun `the words stored are the reader's, less the edges`() {
        // Trimmed at the ends only. The blank lines inside a note are the reader's
        // paragraphing and collapsing them would be editing what they wrote.
        assertEquals(
            "One thought.\n\nAnd another.",
            NoteEdit.tidy("  One thought.\n\nAnd another.\n "),
        )
    }

    @Test
    fun `a draft knows whether there is anything to delete`() {
        // Delete is only ever offered on a note that exists. Offering it on an empty
        // sheet is a control that cannot do anything, which is the same fault as a
        // dead action on the selection bar.
        assertFalse("Delete offered on a blank sheet", NoteEdit.canDelete(draft()))
        assertFalse(
            "Delete offered for words that have not been saved yet",
            NoteEdit.canDelete(draft(text = "Not saved yet.")),
        )
        assertTrue(NoteEdit.canDelete(draft(saved = "A thought.", id = 3)))
    }

    @Test
    fun `a draft opens on what was saved, so reading one is not editing it`() {
        val opened = NoteDraft(span = span, snippet = "s", saved = "A thought.", bookmarkId = 3)
        assertEquals("A thought.", opened.text)
        assertEquals(NoteOutcome.NOTHING, NoteEdit.outcome(opened))
    }
}
