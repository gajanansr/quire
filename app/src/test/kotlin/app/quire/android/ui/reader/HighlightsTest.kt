package app.quire.android.ui.reader

import app.quire.android.data.SavedHighlight
import app.quire.android.ui.theme.HighlightColour
import app.quire.core.reading.TextAnchor
import app.quire.core.reading.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tapping a highlight finds the right one.
 *
 * This is the whole of "a highlight has options": the reader taps the mark and the
 * colours and Remove appear. Get it wrong and the reader recolours or deletes a
 * passage they were not pointing at — and the failure looks like a rendering glitch
 * rather than a lost mark, because the wrong highlight is the one that changes.
 */
class HighlightsTest {

    private fun mark(
        id: Long,
        fromBlock: Int,
        fromChar: Int,
        toBlock: Int,
        toChar: Int,
        colour: HighlightColour = HighlightColour.KEEP,
    ) = SavedHighlight(
        id = id,
        span = TextSpan(TextAnchor(fromBlock, fromChar), TextAnchor(toBlock, toChar)),
        colour = colour,
    )

    private val paragraph = mark(1, 3, 0, 3, 200)

    @Test
    fun `a tap inside a highlight finds it`() {
        assertEquals(1L, Highlights.at(listOf(paragraph), TextAnchor(3, 100))?.id)
    }

    @Test
    fun `a tap before or after a highlight finds nothing`() {
        assertNull(Highlights.at(listOf(mark(1, 3, 40, 3, 90)), TextAnchor(3, 39)))
        assertNull(Highlights.at(listOf(mark(1, 3, 40, 3, 90)), TextAnchor(3, 90)))
    }

    @Test
    fun `the first character is inside and the last is not`() {
        // The end is exclusive everywhere in this model — TextSpan.contains says so —
        // and the boundary has to agree, or the character after a mark opens it.
        val one = listOf(mark(1, 3, 40, 3, 90))
        assertEquals(1L, Highlights.at(one, TextAnchor(3, 40))?.id)
        assertEquals(1L, Highlights.at(one, TextAnchor(3, 89))?.id)
        assertNull(Highlights.at(one, TextAnchor(3, 90)))
    }

    @Test
    fun `a tap in another block finds nothing`() {
        assertNull(Highlights.at(listOf(paragraph), TextAnchor(2, 100)))
        assertNull(Highlights.at(listOf(paragraph), TextAnchor(4, 0)))
    }

    @Test
    fun `a tap in the middle of a highlight that spans blocks finds it`() {
        val across = mark(7, 3, 120, 6, 40)
        assertEquals(7L, Highlights.at(listOf(across), TextAnchor(4, 0))?.id)
        assertEquals(7L, Highlights.at(listOf(across), TextAnchor(5, 900))?.id)
        assertNull(Highlights.at(listOf(across), TextAnchor(6, 40)))
    }

    @Test
    fun `a sentence marked inside a marked paragraph is the one a tap finds`() {
        // The tie-break, and the reason it exists: taking the first match would make
        // the inner mark unreachable for ever — no gesture could select it, so its
        // colour could never be changed and it could never be removed.
        val sentence = mark(2, 3, 80, 3, 120, HighlightColour.DOUBT)
        val both = listOf(paragraph, sentence)
        assertEquals(2L, Highlights.at(both, TextAnchor(3, 100))?.id)
        assertEquals(2L, Highlights.at(both.reversed(), TextAnchor(3, 100))?.id)
        // Outside the sentence but inside the paragraph, the paragraph still answers.
        assertEquals(1L, Highlights.at(both, TextAnchor(3, 10))?.id)
    }

    @Test
    fun `a short mark in a later block beats a long one that swallows it`() {
        // Blocks have to outweigh characters. Character offsets in two different
        // blocks are not comparable — offset 40 of block 3 and offset 40 of block 9
        // are different distances into the chapter — so comparing them directly would
        // call a four-paragraph mark "shorter" than a phrase.
        val whole = mark(1, 3, 0, 9, 500)
        val phrase = mark(2, 5, 10, 5, 30)
        assertEquals(2L, Highlights.at(listOf(whole, phrase), TextAnchor(5, 20))?.id)
    }

    @Test
    fun `a tap the page could not resolve hits nothing`() {
        // The margin, or the gap under the last paragraph. It closes the options
        // rather than reopening them on whatever was nearest.
        assertNull(Highlights.at(listOf(paragraph), null))
    }

    @Test
    fun `a page with no highlights answers nothing rather than failing`() {
        assertNull(Highlights.at(emptyList(), TextAnchor(3, 100)))
    }

    @Test
    fun `the colour comes back with the mark, so the picker can ring the current one`() {
        val marked = mark(4, 1, 0, 1, 50, HighlightColour.LOVELY)
        assertEquals(HighlightColour.LOVELY, Highlights.at(listOf(marked), TextAnchor(1, 10))?.colour)
    }
}
