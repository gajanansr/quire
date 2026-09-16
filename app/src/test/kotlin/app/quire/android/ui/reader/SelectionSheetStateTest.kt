package app.quire.android.ui.reader

import app.quire.android.ui.note.NoteDraft
import app.quire.core.reading.TextAnchor
import app.quire.core.reading.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things a chosen passage can open, and what each one is anchored to.
 *
 * They differ in exactly one way and it is the interesting one. The overflow acts on
 * whatever is selected *now* — it is a longer version of the bar, and if the passage
 * goes the sheet is about nothing. The note sheet carries the span it was opened
 * with, so it survives the selection being cleared underneath it: a reader typing a
 * paragraph about chapter nine must not have their words re-attached, or dropped,
 * because something took the highlight away.
 */
class SelectionSheetStateTest {

    private val span = TextSpan(TextAnchor(7, 120), TextAnchor(7, 186))
    private val chosen = ReaderState(selection = span, selectionOrigin = span)
    private val draft = NoteDraft(span = span, snippet = "The words they kept.")

    @Test
    fun `opening the overflow keeps the passage it is about`() {
        val open = ReaderTransitions.selectionMoreOpened(chosen)
        assertTrue(open.selectionMore)
        assertEquals("the passage went away with the bar", span, open.selection)
    }

    @Test
    fun `losing the passage closes the overflow`() {
        // The sheet is a longer version of the action bar and acts on the live
        // selection. Left open over nothing, Share would send an empty string and
        // Translate would open another app on a blank screen.
        val open = ReaderTransitions.selectionMoreOpened(chosen)
        assertFalse(ReaderTransitions.selectionCleared(open).selectionMore)
    }

    @Test
    fun `the note sheet keeps its own passage when the selection goes`() {
        // The opposite rule, and the reason NoteDraft carries a span at all. A note
        // is written over seconds or minutes; the selection under it can be cleared
        // by a stray tap, and a note saved against "whatever is selected now" would
        // then attach itself to the wrong characters or to none.
        val writing = ReaderTransitions.noteOpened(chosen, draft)
        val cleared = ReaderTransitions.selectionCleared(writing)

        assertNull(cleared.selection)
        assertEquals(span, cleared.note?.span)
    }

    @Test
    fun `opening the note sheet closes the overflow`() {
        // Two modal sheets at once is a stack the reader has to dismiss twice, and
        // the second dismissal looks like the first one having failed.
        val open = ReaderTransitions.selectionMoreOpened(chosen)
        val writing = ReaderTransitions.noteOpened(open, draft)
        assertFalse("the overflow stayed open under the note sheet", writing.selectionMore)
        assertNotNull(writing.note)
    }

    @Test
    fun `typing goes into the draft and leaves what was saved alone`() {
        // `saved` is the comparison every decision in NoteEdit is made against. If
        // typing moved it too, nothing would ever count as a change and Save would
        // never light up.
        val writing = ReaderTransitions.noteOpened(
            chosen, draft.copy(saved = "A thought.", bookmarkId = 3),
        )
        val typed = ReaderTransitions.noteTyped(writing, "A better one.")

        assertEquals("A better one.", typed.note?.text)
        assertEquals("A thought.", typed.note?.saved)
    }

    @Test
    fun `typing with no sheet open changes nothing`() {
        // A keystroke can arrive after the sheet has gone: the field is still
        // composed for a frame while it animates out.
        assertEquals(chosen, ReaderTransitions.noteTyped(chosen, "orphaned"))
    }

    @Test
    fun `closing the note sheet does not decide what happens to the passage`() {
        // Saving clears the selection, because the mark is now on the page and a
        // selection over it would only hide it. Cancelling leaves it, because the
        // reader may well want a different action on the same words. Both go through
        // here, so here must do neither.
        val writing = ReaderTransitions.noteOpened(chosen, draft)
        val closed = ReaderTransitions.noteClosed(writing)

        assertNull(closed.note)
        assertEquals(span, closed.selection)
    }

    @Test
    fun `a chosen passage hides the chrome, and a sheet over it does not bring it back`() {
        // The bars sit exactly where these sheets do. Both visible at once covers the
        // page the reader is choosing words from.
        val open = ReaderTransitions.selectionMoreOpened(chosen.copy(chromeVisible = true))
        assertFalse("the bottom bar came back under the overflow", open.chromeVisible)
    }
}
