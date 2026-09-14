package app.quire.android.ui.reader

import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.reading.SelectionEdge
import app.quire.core.reading.TextAnchor
import app.quire.core.reading.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Reader does with a selection, as a sequence of touches.
 *
 * The complaint these answer is *"the text selection doesnt work well like it works
 * on other apps"*. Broken down, that was four things: the far end was the only one
 * that could move, the selection froze the moment the finger lifted, a sweep
 * backwards dropped the word that had been pressed, and any stray tap destroyed the
 * lot. Each has a test here, written as the gesture that produced it.
 */
class ReaderSelectionTest {

    private val first = "Bagel and cream cheese, classic combo."
    private val second = "Thanks, Avinash, I said."

    private fun state(): ReaderState {
        val chapter = Chapter(
            index = 0,
            title = "One",
            blocks = listOf(
                ContentBlock.Paragraph(listOf(InlineSpan(first))),
                ContentBlock.Paragraph(listOf(InlineSpan(second))),
            ),
            startCharOffset = 0,
            charCount = first.length + second.length,
        )
        return ReaderState(loading = false, chapter = chapter, chapterCount = 1)
    }

    private fun anchor(block: Int, char: Int) = TextAnchor(block, char)

    private fun ReaderState.text(): String {
        val span = selection ?: return ""
        return chapter!!.blockTexts[span.start.blockIndex]
            .substring(span.start.charOffset, span.end.charOffset)
    }

    // ------------------------------------------------------------ the press

    @Test
    fun `a long press takes the whole word under the finger`() {
        val pressed = ReaderTransitions.selectionStarted(state(), anchor(0, 12))
        assertEquals("cream", pressed.text())
        assertTrue("the press did not start a selection", pressed.hasSelection)
    }

    @Test
    fun `a long press hides the chrome so it cannot cover the passage`() {
        val chromed = state().copy(chromeVisible = true)
        assertFalse(ReaderTransitions.selectionStarted(chromed, anchor(0, 12)).chromeVisible)
    }

    // ------------------------------------------------------------ the sweep

    @Test
    fun `sweeping backward keeps the word that was pressed`() {
        // The old model anchored on the pressed word's *start*, so dragging left
        // selected up to that point and the word under the finger silently dropped
        // out of its own selection.
        val swept = ReaderTransitions.selectionExtended(
            ReaderTransitions.selectionStarted(state(), anchor(0, 12)),
            anchor(0, 7),
        )
        assertEquals("and cream", swept.text())
    }

    @Test
    fun `sweeping forward grows by whole words`() {
        val swept = ReaderTransitions.selectionExtended(
            ReaderTransitions.selectionStarted(state(), anchor(0, 12)),
            anchor(0, 17),
        )
        assertEquals("cream cheese", swept.text())
    }

    @Test
    fun `a sweep with no press behind it changes nothing`() {
        val bare = state()
        assertEquals(bare, ReaderTransitions.selectionExtended(bare, anchor(0, 5)))
    }

    // ---------------------------------------------------------- the handles

    @Test
    fun `dragging the start handle grows the passage backwards`() {
        // The motion the reader could not make at all: the anchor was always the
        // long-press point, so nothing could ever be added before it.
        val pressed = ReaderTransitions.selectionStarted(state(), anchor(0, 12))
        val held = ReaderTransitions.handleGrabbed(pressed, SelectionEdge.START)
        val moved = ReaderTransitions.handleMoved(held, anchor(0, 6))

        assertEquals("and cream", moved.text())
    }

    @Test
    fun `dragging the end handle is accurate to the character`() {
        // Deliberately different from the sweep. A reader reaches for a handle
        // precisely because the word-sized sweep overshot, so snapping here would
        // make the handles useless for the one thing they are for.
        val pressed = ReaderTransitions.selectionStarted(state(), anchor(0, 12))
        val held = ReaderTransitions.handleGrabbed(pressed, SelectionEdge.END)
        val moved = ReaderTransitions.handleMoved(held, anchor(0, 18))

        assertEquals("cream ch", moved.text())
    }

    @Test
    fun `a handle drag with nothing grabbed is ignored`() {
        val pressed = ReaderTransitions.selectionStarted(state(), anchor(0, 12))
        assertEquals(pressed, ReaderTransitions.handleMoved(pressed, anchor(0, 30)))
    }

    @Test
    fun `dragging a handle past the other keeps hold of the finger`() {
        val pressed = ReaderTransitions.selectionStarted(state(), anchor(0, 12))
        val held = ReaderTransitions.handleGrabbed(pressed, SelectionEdge.END)
        val crossed = ReaderTransitions.handleMoved(held, anchor(0, 6))

        assertEquals("and ", crossed.text())
        assertEquals(
            "the finger is now holding the start, and the state must agree",
            SelectionEdge.START, crossed.selectionEdge,
        )
    }

    @Test
    fun `releasing a handle lets go of it without losing the passage`() {
        val pressed = ReaderTransitions.selectionStarted(state(), anchor(0, 12))
        val held = ReaderTransitions.handleGrabbed(pressed, SelectionEdge.END)
        val released = ReaderTransitions.handleReleased(held)

        assertNull(released.selectionEdge)
        assertEquals("cream", released.text())
    }

    @Test
    fun `grabbing a handle hides the chrome`() {
        val chromed = ReaderTransitions.selectionStarted(state(), anchor(0, 12))
            .copy(chromeVisible = true)
        assertFalse(ReaderTransitions.handleGrabbed(chromed, SelectionEdge.END).chromeVisible)
    }

    // ---------------------------------------------------------------- taps

    @Test
    fun `a tap inside the passage keeps it`() {
        // *"Selection is lost on any stray tap, with no way back."* With two handles
        // on screen the passage is exactly where the thumb goes, so clearing on this
        // tap is the specific way the old behaviour destroyed work.
        val pressed = ReaderTransitions.selectionStarted(state(), anchor(0, 12))
        val tapped = ReaderTransitions.tappedWhileSelecting(pressed, anchor(0, 13))

        assertTrue("a tap on the selected words cleared it", tapped.hasSelection)
    }

    @Test
    fun `a tap outside the passage clears it`() {
        val pressed = ReaderTransitions.selectionStarted(state(), anchor(0, 12))
        val tapped = ReaderTransitions.tappedWhileSelecting(pressed, anchor(0, 30))

        assertFalse(tapped.hasSelection)
        assertNull(tapped.selectionEdge)
    }

    @Test
    fun `a tap that resolves to nothing clears, as tapping the margin should`() {
        val pressed = ReaderTransitions.selectionStarted(state(), anchor(0, 12))
        assertFalse(ReaderTransitions.tappedWhileSelecting(pressed, null).hasSelection)
    }

    @Test
    fun `a tap in another block clears`() {
        val pressed = ReaderTransitions.selectionStarted(state(), anchor(0, 12))
        assertFalse(ReaderTransitions.tappedWhileSelecting(pressed, anchor(1, 3)).hasSelection)
    }

    @Test
    fun `clearing a selection lets go of every part of it`() {
        val held = ReaderTransitions.handleGrabbed(
            ReaderTransitions.selectionStarted(state(), anchor(0, 12)),
            SelectionEdge.END,
        )
        val cleared = ReaderTransitions.selectionCleared(held)

        assertNull(cleared.selection)
        assertNull(cleared.selectionEdge)
        assertNull("a stale origin would resurrect the old passage", cleared.selectionOrigin)
    }

    // --------------------------------------------------------- span arithmetic

    @Test
    fun `a span contains its own characters and not the one it ends before`() {
        // What decides whether a tap is inside the passage. The end is exclusive, the
        // same way every other piece of this model treats it.
        val span = TextSpan.of(anchor(0, 10), anchor(0, 15))
        assertTrue("the first selected character is outside its own span", anchor(0, 10) in span)
        assertTrue(anchor(0, 14) in span)
        assertFalse("the exclusive end is inside the span", anchor(0, 15) in span)
        assertFalse(anchor(0, 9) in span)
    }
}
